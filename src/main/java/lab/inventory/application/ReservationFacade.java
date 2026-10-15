package lab.inventory.application;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import lab.inventory.application.PoolRefillService.RefillResult;
import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.domain.exception.PoolNotReadyException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.persistence.ReservationRepository;
import lab.inventory.persistence.SqlErrors;
import org.springframework.stereotype.Service;

/**
 * 不持有事务的编排层：规范化、幂等、池不足时的事务外补充与有界重试、错误映射。
 * 每个事务回调完全退出、连接归还之后，才启动下一个事务。
 */
@Service
public class ReservationFacade {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public record ReserveOutcome(Reservation reservation, boolean replayed) {
    }

    private final ReservationTransactionService transactions;
    private final PoolRefillService refills;
    private final ReservationRepository reservations;
    private final InventoryLedgerRepository ledgers;
    private final TransactionRunner runner;
    private final InventoryMetrics metrics;
    private final StockholdProperties.Reserve config;
    private final Semaphore admission;

    public ReservationFacade(ReservationTransactionService transactions, PoolRefillService refills,
            ReservationRepository reservations, InventoryLedgerRepository ledgers, TransactionRunner runner,
            InventoryMetrics metrics, StockholdProperties properties) {
        this.transactions = transactions;
        this.refills = refills;
        this.reservations = reservations;
        this.ledgers = ledgers;
        this.runner = runner;
        this.metrics = metrics;
        this.config = properties.reserve();
        this.admission = new Semaphore(Math.max(1, config.maxConcurrent()));
    }

    public ReserveOutcome reserve(long shopId, String idempotencyKey, Integer ttlSeconds,
            List<CanonicalReservationRequest.RawLine> rawLines) {
        long start = System.nanoTime();
        String outcome = "error";
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.RESERVE)) {
            requireToken(idempotencyKey, "Idempotency-Key");
            CanonicalReservationRequest request = CanonicalReservationRequest.of(shopId, ttlSeconds, rawLines);
            ReserveOutcome result = reserveAdmitted(idempotencyKey, request);
            outcome = result.replayed() ? "replayed" : "created";
            return result;
        } catch (ApiException e) {
            outcome = e.code().name().toLowerCase();
            throw e;
        } finally {
            metrics.recordOperation(BusinessOperation.RESERVE, outcome, System.nanoTime() - start);
        }
    }

    /** 有限并发入口：重试不得扩大为无界线程或无界排队。 */
    private ReserveOutcome reserveAdmitted(String idempotencyKey, CanonicalReservationRequest request) {
        boolean admitted;
        try {
            admitted = admission.tryAcquire(config.admissionWaitMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admitted = false;
        }
        if (!admitted) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Too many concurrent reservation requests.");
        }
        try {
            return reserveWithRetry(idempotencyKey, request);
        } finally {
            admission.release();
        }
    }

    private ReserveOutcome reserveWithRetry(String idempotencyKey, CanonicalReservationRequest request) {
        Optional<ReserveOutcome> replay = findReplay(idempotencyKey, request);
        if (replay.isPresent()) {
            return replay.get();
        }
        checkCapacities(request);

        UUID reservationId = UUID.randomUUID();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.budgetMillis());
        for (int attempt = 1;; attempt++) {
            String retryReason;
            boolean backoff = true;
            try {
                return new ReserveOutcome(transactions.tryReserve(reservationId, idempotencyKey, request), false);
            } catch (PoolNotReadyException e) {
                // reserve 事务已整体结束并归还连接，现在才允许补充（锁序 ledger -> units）。
                metrics.poolMiss();
                backoff = !refillLacking(e.lackingLines());
                retryReason = "pool";
            } catch (RuntimeException e) {
                if (SqlErrors.isDuplicateKey(e, ReservationRepository.IDEMPOTENCY_INDEX)) {
                    // 并发首建由唯一约束仲裁；当前事务已回滚，再用新的读取查询已有记录。
                    replay = findReplay(idempotencyKey, request);
                    if (replay.isPresent()) {
                        return replay.get();
                    }
                    retryReason = "idempotency";
                } else if (SqlErrors.isTransientLockFailure(e)) {
                    retryReason = "lock";
                } else {
                    throw e;
                }
            }

            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (attempt >= config.maxAttempts() || remainingMillis <= 0) {
                throw ApiException.busy("Inventory is temporarily contended; retry with the same idempotency key.");
            }
            metrics.reserveRetry(retryReason);
            if (backoff) {
                runner.sleepJitter(remainingMillis);
            }
        }
    }

    /**
     * 对每个不足维度执行独立补充事务，并在账本锁下的观察值上区分真实不足与临时竞争。
     *
     * @return true 表示所有不足维度都新补入了单位，可以立即重试而不必退避
     */
    private boolean refillLacking(List<CanonicalReservationRequest.Line> lacking) {
        boolean allRefilled = true;
        for (CanonicalReservationRequest.Line line : lacking) {
            RefillResult result = refills.refillOneDimension(line.key(), true);
            if (result.potentialAvailable() < line.quantity()) {
                throw ApiException.forKey(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough reservable stock for the requested quantity.", line.key());
            }
            allRefilled &= result.insertedUnits() > 0;
        }
        return allRefilled;
    }

    private Optional<ReserveOutcome> findReplay(String idempotencyKey, CanonicalReservationRequest request) {
        return reservations.findByIdempotencyKey(request.shopId(), idempotencyKey).map(existing -> {
            if (!Arrays.equals(existing.header().requestHash(), request.sha256())) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "Idempotency key was already used with a different request.");
            }
            return new ReserveOutcome(existing, true);
        });
    }

    /** 单维度数量不得超过池容量；容量创建后固定，因此在事务前用非锁定读校验。 */
    private void checkCapacities(CanonicalReservationRequest request) {
        List<InventoryKey> keys = request.lines().stream().map(CanonicalReservationRequest.Line::key).toList();
        Map<InventoryKey, Integer> capacities = ledgers.findCapacities(request.shopId(), keys);
        for (CanonicalReservationRequest.Line line : request.lines()) {
            Integer capacity = capacities.get(line.key());
            if (capacity == null) {
                throw ApiException.inventoryNotFound(line.key());
            }
            if (line.quantity() > capacity) {
                throw ApiException.forKey(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                        "Quantity exceeds the pool capacity of this item/location.", line.key());
            }
        }
    }

    public Reservation claim(long shopId, UUID reservationId, String paymentReference) {
        requireToken(paymentReference, "paymentReference");
        return timed(BusinessOperation.CLAIM, () -> runWithRetry(BusinessOperation.CLAIM,
                () -> transactions.claim(shopId, reservationId, paymentReference)));
    }

    public Reservation cancel(long shopId, UUID reservationId) {
        return timed(BusinessOperation.CANCEL, () -> runWithRetry(BusinessOperation.CANCEL,
                () -> transactions.cancel(shopId, reservationId)));
    }

    public Reservation get(long shopId, UUID reservationId) {
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.QUERY)) {
            return reservations.find(shopId, reservationId).orElseThrow(ApiException::reservationNotFound);
        }
    }

    public Reservation getByIdempotencyKey(long shopId, String idempotencyKey) {
        requireToken(idempotencyKey, "key");
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.QUERY)) {
            return reservations.findByIdempotencyKey(shopId, idempotencyKey)
                    .orElseThrow(ApiException::reservationNotFound);
        }
    }

    /** claim / cancel 自身幂等，死锁或锁等待超时后可以整事务重放。 */
    private Reservation runWithRetry(BusinessOperation operation, java.util.function.Supplier<Reservation> body) {
        int maxAttempts = Math.max(1, config.maxAttempts());
        for (int attempt = 1;; attempt++) {
            try {
                return body.get();
            } catch (RuntimeException e) {
                if (!SqlErrors.isTransientLockFailure(e)) {
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    throw ApiException.busy("Inventory is temporarily contended; retry the request.");
                }
                metrics.transientRetry(operation);
                runner.sleepJitter(Long.MAX_VALUE);
            }
        }
    }

    private Reservation timed(BusinessOperation operation, java.util.function.Supplier<Reservation> body) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            Reservation result = body.get();
            outcome = "ok";
            return result;
        } catch (ApiException e) {
            outcome = e.code().name().toLowerCase();
            throw e;
        } finally {
            metrics.recordOperation(operation, outcome, System.nanoTime() - start);
        }
    }

    private static void requireToken(String value, String name) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw ApiException.invalidArgument(name + " must be 1..128 characters of [A-Za-z0-9._:-].");
        }
    }
}
