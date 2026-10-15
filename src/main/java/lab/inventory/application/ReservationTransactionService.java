package lab.inventory.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.domain.exception.PoolNotReadyException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.persistence.ReservationRepository;
import lab.inventory.persistence.ReservationUnitRepository;
import lab.inventory.persistence.SqlErrors;
import org.springframework.stereotype.Service;

/**
 * 每个公开方法是一个完整的短事务，不重试、不休眠、不等待库存池。
 * 锁顺序：reserve 为 新头 -> units -> details；claim / cancel / expiry 为 已有头 -> ledger。
 */
@Service
public class ReservationTransactionService {

    private final TransactionRunner transactions;
    private final ReservationRepository reservations;
    private final ReservationUnitRepository units;
    private final InventoryLedgerRepository ledgers;
    private final FaultInjector faults;
    private final InventoryMetrics metrics;
    private final boolean unionAllSelect;

    public ReservationTransactionService(TransactionRunner transactions, ReservationRepository reservations,
            ReservationUnitRepository units, InventoryLedgerRepository ledgers, FaultInjector faults,
            InventoryMetrics metrics, StockholdProperties properties) {
        this.unionAllSelect = properties.reserve().unionAllSelect();
        this.transactions = transactions;
        this.reservations = reservations;
        this.units = units;
        this.ledgers = ledgers;
        this.faults = faults;
        this.metrics = metrics;
    }

    /**
     * 正常预留事务（7.2）。不取账本行锁，也绝不在此事务中补充。
     *
     * @throws PoolNotReadyException 任一维度领取不足；事务已整体回滚
     */
    public Reservation tryReserve(UUID reservationId, String idempotencyKey, CanonicalReservationRequest request) {
        return transactions.execute(BusinessOperation.RESERVE, () -> {
            // TTL 从成功创建预留的这个事务的数据库时间开始计算。
            Instant now = reservations.databaseNow();
            Instant expiresAt = now.plusSeconds(request.ttlSeconds());
            reservations.insertHeader(reservationId, idempotencyKey, request, now, expiresAt);

            List<List<byte[]>> locked = lockUnits(request.lines());
            List<CanonicalReservationRequest.Line> lacking = new ArrayList<>();
            for (int i = 0; i < request.lines().size(); i++) {
                if (locked.get(i).size() < request.lines().get(i).quantity()) {
                    lacking.add(request.lines().get(i));
                }
            }
            if (!lacking.isEmpty()) {
                // 走完所有维度再失败，外层一次就能补充全部不足维度；回滚会释放已锁单位。
                throw new PoolNotReadyException(lacking);
            }

            for (int i = 0; i < request.lines().size(); i++) {
                CanonicalReservationRequest.Line line = request.lines().get(i);
                if (units.deleteExact(line.key(), locked.get(i)) != line.quantity()) {
                    throw violation("Deleted unit count differs from the locked unit count.", line.key());
                }
            }
            faults.at(FaultInjector.Point.RESERVE_AFTER_UNIT_DELETE);

            List<Reservation.Line> lines = new ArrayList<>(request.lines().size());
            for (int i = 0; i < request.lines().size(); i++) {
                CanonicalReservationRequest.Line line = request.lines().get(i);
                reservations.insertLine(request.shopId(), reservationId, i + 1, line);
                lines.add(new Reservation.Line(i + 1, line.key().inventoryItemId(), line.key().locationId(),
                        line.quantity()));
                if (i == 0) {
                    faults.at(FaultInjector.Point.RESERVE_AFTER_FIRST_LINE);
                }
            }
            Reservation.Header header = new Reservation.Header(request.shopId(), reservationId, idempotencyKey,
                    request.sha256(), ReservationStatus.ACTIVE, request.ttlSeconds(), request.totalQuantity(),
                    lines.size(), null, expiresAt, null, null, null, now);
            return new Reservation(header, lines);
        });
    }

    /** 默认按 InventoryKey 升序逐维度锁定；功能开关打开且多维度时走 UNION ALL 单次往返。 */
    private List<List<byte[]>> lockUnits(List<CanonicalReservationRequest.Line> lines) {
        if (unionAllSelect && lines.size() > 1) {
            return units.lockAvailableUnionAll(lines);
        }
        List<List<byte[]>> locked = new ArrayList<>(lines.size());
        for (CanonicalReservationRequest.Line line : lines) {
            locked.add(units.lockAvailable(line.key(), line.quantity()));
        }
        return locked;
    }

    /** 确认扣减（7.6）：H -= n，A -= n，头转 CLAIMED。 */
    public Reservation claim(long shopId, UUID reservationId, String paymentReference) {
        return transactions.execute(BusinessOperation.CLAIM, () -> {
            Reservation.Header header = reservations.lockHeader(shopId, reservationId)
                    .orElseThrow(ApiException::reservationNotFound);
            List<Reservation.Line> lines = reservations.findLines(shopId, reservationId);
            switch (header.status()) {
                case CLAIMED -> {
                    // 已 CLAIMED 的重复 claim 先走幂等返回，不因后来经过 expires_at 而失效。
                    if (paymentReference.equals(header.paymentReference())) {
                        return new Reservation(header, lines);
                    }
                    throw new ApiException(ErrorCode.PAYMENT_REFERENCE_CONFLICT,
                            "Reservation is already claimed with a different payment reference.");
                }
                case CANCELLED -> throw new ApiException(ErrorCode.INVALID_RESERVATION_STATE,
                        "Reservation is cancelled.");
                case EXPIRED -> throw new ApiException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired.");
                case ACTIVE -> {
                }
            }

            // 确实获得头锁之后才采样数据库时间；判断点是此刻，不是提交完成时刻。
            Instant now = reservations.databaseNow();
            if (!header.expiresAt().isAfter(now)) {
                throw new ApiException(ErrorCode.RESERVATION_EXPIRED,
                        "Reservation has expired and can no longer be claimed.");
            }

            // 明细按 InventoryKey 升序存储；按此顺序执行条件 UPDATE 即按统一顺序取得账本行锁。
            for (Reservation.Line line : lines) {
                InventoryKey key = line.key(shopId);
                if (ledgers.claim(key, line.quantity()) != 1) {
                    throw violation("Ledger cannot cover a claimed reservation line.", key);
                }
            }
            faults.at(FaultInjector.Point.CLAIM_AFTER_LEDGER_UPDATE);

            try {
                if (reservations.markClaimed(shopId, reservationId, paymentReference, now) != 1) {
                    throw new IllegalStateException("Reservation header changed while its row lock was held");
                }
            } catch (RuntimeException e) {
                if (SqlErrors.isDuplicateKey(e, ReservationRepository.PAYMENT_INDEX)) {
                    // 抛出异常使账本扣减一并回滚。
                    throw new ApiException(ErrorCode.PAYMENT_REFERENCE_CONFLICT,
                            "Payment reference is already used by another reservation.");
                }
                throw e;
            }
            return new Reservation(transition(header, ReservationStatus.CLAIMED, paymentReference, now), lines);
        });
    }

    /** 取消（7.7）：A -= n，H 不变；到期但未清理的 ACTIVE 也可以取消。 */
    public Reservation cancel(long shopId, UUID reservationId) {
        return transactions.execute(BusinessOperation.CANCEL, () -> {
            Reservation.Header header = reservations.lockHeader(shopId, reservationId)
                    .orElseThrow(ApiException::reservationNotFound);
            List<Reservation.Line> lines = reservations.findLines(shopId, reservationId);
            switch (header.status()) {
                case CANCELLED -> {
                    return new Reservation(header, lines);
                }
                case CLAIMED -> throw new ApiException(ErrorCode.INVALID_RESERVATION_STATE,
                        "Reservation is already claimed.");
                case EXPIRED -> throw new ApiException(ErrorCode.RESERVATION_EXPIRED,
                        "Reservation has expired; its allocation was already released.");
                case ACTIVE -> {
                }
            }
            Instant now = reservations.databaseNow();
            releaseAllocation(shopId, lines);
            if (reservations.markCancelled(shopId, reservationId, now) != 1) {
                throw new IllegalStateException("Reservation header changed while its row lock was held");
            }
            return new Reservation(transition(header, ReservationStatus.CANCELLED, null, now), lines);
        });
    }

    /**
     * 到期释放一个候选。扫描结果只是提示，这里持头锁后重新检查状态和数据库时间。
     *
     * @return true 表示本事务完成了释放；false 表示已被其他事务处理或尚未到期
     */
    public boolean expire(long shopId, UUID reservationId) {
        return transactions.execute(BusinessOperation.EXPIRY, () -> {
            Reservation.Header header = reservations.lockHeader(shopId, reservationId).orElse(null);
            if (header == null || header.status() != ReservationStatus.ACTIVE) {
                return false;
            }
            Instant now = reservations.databaseNow();
            if (header.expiresAt().isAfter(now)) {
                return false;
            }
            releaseAllocation(shopId, reservations.findLines(shopId, reservationId));
            if (reservations.markExpired(shopId, reservationId, now) != 1) {
                throw new IllegalStateException("Reservation header changed while its row lock was held");
            }
            return true;
        });
    }

    /** 释放的额度回到 F，不直接插回单位池，避免形成 units 相关的反向锁路径。 */
    private void releaseAllocation(long shopId, List<Reservation.Line> lines) {
        for (Reservation.Line line : lines) {
            InventoryKey key = line.key(shopId);
            if (ledgers.release(key, line.quantity()) != 1) {
                throw violation("Ledger allocation is smaller than a released reservation line.", key);
            }
        }
        faults.at(FaultInjector.Point.RELEASE_AFTER_LEDGER_UPDATE);
    }

    private ApiException violation(String message, InventoryKey key) {
        metrics.invariantViolation();
        return ApiException.invariantViolation(message, key);
    }

    private static Reservation.Header transition(Reservation.Header header, ReservationStatus status,
            String paymentReference, Instant at) {
        return new Reservation.Header(header.shopId(), header.reservationId(), header.idempotencyKey(),
                header.requestHash(), status, header.ttlSeconds(), header.totalQuantity(), header.lineCount(),
                paymentReference,
                header.expiresAt(),
                status == ReservationStatus.CLAIMED ? at : null,
                status == ReservationStatus.CANCELLED ? at : null,
                status == ReservationStatus.EXPIRED ? at : null,
                header.createdAt());
    }
}
