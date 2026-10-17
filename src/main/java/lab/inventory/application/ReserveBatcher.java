package lab.inventory.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.PoolNotReadyException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.persistence.ReservationRepository;
import lab.inventory.persistence.ReservationUnitRepository;
import lab.inventory.persistence.SqlErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionStatus;

/**
 * 跨结账批量预留（文章：把多个结账的预留合并进一次 SKIP LOCKED 查询以减少连接使用，“有帮助但增加了复杂度”）。
 *
 * <p>只合并单维度的请求。同一维度的第一个请求成为批次的领头者，等待一个很短的窗口让其他请求加入；
 * 随后用一个事务、一次 SKIP LOCKED 锁定整批所需的单位，按到达顺序分给各成员。每个成员在自己的保存点内
 * 写头、删单位、写明细：某个成员失败（例如幂等键重复）只回滚它自己。拿不到足够单位的成员得到
 * {@link PoolNotReadyException}，与单独预留完全相同地交给外层补充和重试。成员的结果在提交成功之后才发布。
 *
 * <p>默认关闭（stockhold.reserve.batching.enabled）。
 */
@Component
public class ReserveBatcher {

    private record Member(UUID reservationId, String idempotencyKey, CanonicalReservationRequest request,
            CompletableFuture<Reservation> result) {
    }

    private static final class Batch {
        private final List<Member> members = new ArrayList<>();
        private boolean closed;
    }

    private static final long WAIT_LIMIT_SECONDS = 15;

    private final boolean enabled;
    private final int maxBatchSize;
    private final long maxWaitNanos;
    private final TransactionRunner transactions;
    private final ReservationRepository reservations;
    private final ReservationUnitRepository units;
    private final FaultInjector faults;
    private final DistributionSummary batchSizes;
    private final ConcurrentHashMap<InventoryKey, Batch> open = new ConcurrentHashMap<>();

    public ReserveBatcher(@Value("${stockhold.reserve.batching.enabled:false}") boolean enabled,
            @Value("${stockhold.reserve.batching.max-size:32}") int maxBatchSize,
            @Value("${stockhold.reserve.batching.max-wait-micros:1000}") long maxWaitMicros,
            TransactionRunner transactions, ReservationRepository reservations, ReservationUnitRepository units,
            FaultInjector faults, MeterRegistry meters) {
        this.enabled = enabled;
        this.maxBatchSize = Math.max(1, maxBatchSize);
        this.maxWaitNanos = TimeUnit.MICROSECONDS.toNanos(maxWaitMicros);
        this.transactions = transactions;
        this.reservations = reservations;
        this.units = units;
        this.faults = faults;
        this.batchSizes = DistributionSummary.builder("stockhold.reserve.batch.size").register(meters);
    }

    public boolean applies(CanonicalReservationRequest request) {
        return enabled && request.lines().size() == 1;
    }

    /** 语义与 {@link ReservationTransactionService#tryReserve} 相同：成功返回预留，缺行抛 PoolNotReady，其余异常原样抛出。 */
    public Reservation tryReserve(UUID reservationId, String idempotencyKey, CanonicalReservationRequest request) {
        InventoryKey key = request.lines().get(0).key();
        Member member = new Member(reservationId, idempotencyKey, request, new CompletableFuture<>());
        boolean[] leader = new boolean[1];
        Batch batch = open.compute(key, (k, current) -> {
            if (current == null || current.closed || current.members.size() >= maxBatchSize) {
                Batch fresh = new Batch();
                fresh.members.add(member);
                leader[0] = true;
                return fresh;
            }
            current.members.add(member);
            return current;
        });
        if (leader[0]) {
            lead(key, batch);
        }
        try {
            // 领头者的事务受事务超时约束；这里再给一个上限，任何意外都不会让成员无限等待。
            return member.result().get(WAIT_LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new CompletionException(e.getCause());
        } catch (TimeoutException e) {
            throw ApiException.busy("Batched reservation did not complete in time; retry with the same key.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.busy("Interrupted while waiting for a batched reservation.");
        }
    }

    private void lead(InventoryKey key, Batch batch) {
        long deadline = System.nanoTime() + maxWaitNanos;
        while (System.nanoTime() < deadline && size(key, batch) < maxBatchSize) {
            LockSupport.parkNanos(50_000);
        }
        List<Member> members = new ArrayList<>();
        open.computeIfPresent(key, (k, current) -> {
            if (current == batch) {
                current.closed = true;
                members.addAll(current.members);
                return null;
            }
            return current;
        });
        if (members.isEmpty()) {
            // 批次已满、被新批次替换出映射：之后不再有人能加入它，可以直接取出成员。
            batch.closed = true;
            members.addAll(batch.members);
        }
        batchSizes.record(members.size());
        execute(key, members);
    }

    private int size(InventoryKey key, Batch batch) {
        int[] size = new int[1];
        open.computeIfPresent(key, (k, current) -> {
            size[0] = current == batch ? current.members.size() : maxBatchSize;
            return current;
        });
        return size[0];
    }

    private void execute(InventoryKey key, List<Member> members) {
        List<Runnable> publish = new ArrayList<>();
        try {
            transactions.executeWithStatus(BusinessOperation.RESERVE, status -> {
                runBatch(key, members, status, publish);
                return null;
            });
        } catch (Throwable e) {
            // 整个批次事务失败（锁等待超时、死锁、注入故障等）：每个成员都收到同一个异常。
            members.forEach(m -> m.result().completeExceptionally(e));
            if (e instanceof Error error) {
                throw error;
            }
            return;
        }
        publish.forEach(Runnable::run);
    }

    private void runBatch(InventoryKey key, List<Member> members, TransactionStatus status, List<Runnable> publish) {
        Instant now = reservations.databaseNow();
        int total = members.stream().mapToInt(m -> m.request().lines().get(0).quantity()).sum();
        // 一次 SKIP LOCKED 为整批结账锁定单位。
        List<byte[]> locked = units.lockAvailable(key, total);
        int offset = 0;
        for (Member member : members) {
            CanonicalReservationRequest.Line line = member.request().lines().get(0);
            if (offset + line.quantity() > locked.size()) {
                publish.add(() -> member.result().completeExceptionally(new PoolNotReadyException(List.of(line))));
                continue;
            }
            List<byte[]> mine = locked.subList(offset, offset + line.quantity());
            Object savepoint = status.createSavepoint();
            try {
                Instant expiresAt = now.plusSeconds(member.request().ttlSeconds());
                reservations.insertHeader(member.reservationId(), member.idempotencyKey(), member.request(), now,
                        expiresAt);
                if (units.deleteExact(key, mine) != line.quantity()) {
                    throw ApiException.invariantViolation("Deleted unit count differs from the locked unit count.",
                            key);
                }
                faults.at(FaultInjector.Point.RESERVE_AFTER_UNIT_DELETE);
                reservations.insertLine(member.request().shopId(), member.reservationId(), 1, line);
                status.releaseSavepoint(savepoint);
                offset += line.quantity();
                Reservation reservation = reservation(member, line, now, expiresAt);
                publish.add(() -> member.result().complete(reservation));
            } catch (RuntimeException e) {
                if (!SqlErrors.isDuplicateKey(e, ReservationRepository.IDEMPOTENCY_INDEX)) {
                    throw e; // 其他失败让整个批次回滚，与单独预留一致
                }
                // 幂等键重复只回滚这个成员；它的单位留给后面的成员。
                status.rollbackToSavepoint(savepoint);
                publish.add(() -> member.result().completeExceptionally(e));
            }
        }
    }

    private static Reservation reservation(Member member, CanonicalReservationRequest.Line line, Instant now,
            Instant expiresAt) {
        CanonicalReservationRequest request = member.request();
        Reservation.Header header = new Reservation.Header(request.shopId(), member.reservationId(),
                member.idempotencyKey(), request.sha256(), ReservationStatus.ACTIVE, request.ttlSeconds(),
                request.totalQuantity(), 1, null, expiresAt, null, null, null, now);
        return new Reservation(header, List.of(new Reservation.Line(1, line.key().inventoryItemId(),
                line.key().locationId(), line.quantity())));
    }
}
