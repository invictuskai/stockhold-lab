package lab.inventory.benchmark;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;
import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.application.TransactionRunner;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.persistence.ReservationRepository;
import lab.inventory.persistence.ReservationRepository.ExpiryCandidate;
import lab.inventory.persistence.SqlErrors;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 单计数器对照组：预留时条件更新同一行的 reserved_quantity，所有请求竞争同一个行锁。
 * 幂等、状态机、失败语义与单位池方案保持等价，只替换库存占用的数据结构。
 */
@Service
@Profile("benchmark")
public class BaselineCounterService {

    private static final String KEY_PREDICATE = " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ?";

    public record CounterRow(long onHand, long reserved) {
    }

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final TransactionRunner transactions;
    private final ReservationRepository reservations;

    public BaselineCounterService(JdbcTemplate jdbc, DataSource dataSource, TransactionRunner transactions) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.reservations = new ReservationRepository(jdbc, "baseline_reservations", "baseline_reserved_quantities");
    }

    @PostConstruct
    void createTables() {
        new ResourceDatabasePopulator(new ClassPathResource("db/benchmark/baseline_counter.sql")).execute(dataSource);
    }

    /** 重置一个维度的计数器；仅供压测准备数据。 */
    public void seed(InventoryKey key, long onHand) {
        if (onHand < 0) {
            throw ApiException.invalidArgument("onHand must be >= 0.");
        }
        transactions.executeWithRetry(BusinessOperation.BENCHMARK, () -> jdbc.update(tag(
                "INSERT INTO baseline_inventory_counter"
                        + " (shop_id, inventory_item_id, location_id, on_hand_quantity, reserved_quantity)"
                        + " VALUES (?, ?, ?, ?, 0)"
                        + " ON DUPLICATE KEY UPDATE on_hand_quantity = VALUES(on_hand_quantity) + reserved_quantity"),
                key.shopId(), key.inventoryItemId(), key.locationId(), onHand));
    }

    public CounterRow counter(InventoryKey key) {
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.BENCHMARK)) {
            List<CounterRow> rows = jdbc.query(tag("SELECT on_hand_quantity, reserved_quantity"
                    + " FROM baseline_inventory_counter" + KEY_PREDICATE),
                    (rs, i) -> new CounterRow(rs.getLong(1), rs.getLong(2)),
                    key.shopId(), key.inventoryItemId(), key.locationId());
            return rows.stream().findFirst().orElseThrow(() -> ApiException.inventoryNotFound(key));
        }
    }

    public ReserveOutcome reserve(long shopId, String idempotencyKey, Integer ttlSeconds,
            List<CanonicalReservationRequest.RawLine> rawLines) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.invalidArgument("Idempotency-Key must be 1..128 characters of [A-Za-z0-9._:-].");
        }
        CanonicalReservationRequest request = CanonicalReservationRequest.of(shopId, ttlSeconds, rawLines);
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.BENCHMARK)) {
            // 与单位池方案一致：不做事务外的幂等预查，重放由插入头时的唯一约束仲裁。
            UUID reservationId = UUID.randomUUID();
            try {
                return new ReserveOutcome(transactions.executeWithRetry(BusinessOperation.BENCHMARK,
                        () -> reserveInTransaction(reservationId, idempotencyKey, request)), false);
            } catch (RuntimeException e) {
                if (SqlErrors.isDuplicateKey(e, ReservationRepository.IDEMPOTENCY_INDEX)) {
                    return findReplay(idempotencyKey, request).orElseThrow(() -> ApiException.busy(
                            "Concurrent request with the same idempotency key; retry."));
                }
                throw e;
            }
        }
    }

    private Reservation reserveInTransaction(UUID reservationId, String idempotencyKey,
            CanonicalReservationRequest request) {
        Instant now = reservations.databaseNow();
        Instant expiresAt = now.plusSeconds(request.ttlSeconds());
        reservations.insertHeader(reservationId, idempotencyKey, request, now, expiresAt);

        List<Reservation.Line> lines = new ArrayList<>(request.lines().size());
        for (int i = 0; i < request.lines().size(); i++) {
            CanonicalReservationRequest.Line line = request.lines().get(i);
            InventoryKey key = line.key();
            // 热点：同一维度的所有预留都要等待并更新这一行。
            int updated = jdbc.update(tag("UPDATE baseline_inventory_counter"
                    + " SET reserved_quantity = reserved_quantity + ?" + KEY_PREDICATE
                    + " AND on_hand_quantity - reserved_quantity >= ?"),
                    line.quantity(), key.shopId(), key.inventoryItemId(), key.locationId(), line.quantity());
            if (updated != 1) {
                // 已持有或刚等待过该行锁；此处的读取用于区分未初始化与真实不足。
                Integer exists = jdbc.queryForObject(tag("SELECT COUNT(*) FROM baseline_inventory_counter"
                        + KEY_PREDICATE), Integer.class, key.shopId(), key.inventoryItemId(), key.locationId());
                if (exists == null || exists == 0) {
                    throw ApiException.inventoryNotFound(key);
                }
                throw ApiException.forKey(ErrorCode.INSUFFICIENT_STOCK,
                        "Not enough reservable stock for the requested quantity.", key);
            }
            reservations.insertLine(request.shopId(), reservationId, i + 1, line);
            lines.add(new Reservation.Line(i + 1, key.inventoryItemId(), key.locationId(), line.quantity()));
        }
        return new Reservation(new Reservation.Header(request.shopId(), reservationId, idempotencyKey,
                request.sha256(), ReservationStatus.ACTIVE, request.ttlSeconds(), request.totalQuantity(),
                lines.size(), null, expiresAt, null, null, null, now), lines);
    }

    public Reservation claim(long shopId, UUID reservationId, String paymentReference) {
        if (paymentReference == null || !paymentReference.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw ApiException.invalidArgument("paymentReference must be 1..128 characters of [A-Za-z0-9._:-].");
        }
        return transition(shopId, reservationId, () -> {
            Reservation.Header header = lock(shopId, reservationId);
            if (header.status() == ReservationStatus.CLAIMED) {
                if (paymentReference.equals(header.paymentReference())) {
                    return null;
                }
                throw new ApiException(ErrorCode.PAYMENT_REFERENCE_CONFLICT,
                        "Reservation is already claimed with a different payment reference.");
            }
            requireActive(header);
            Instant now = reservations.databaseNow();
            if (!header.expiresAt().isAfter(now)) {
                throw new ApiException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired.");
            }
            updateCounters(shopId, reservationId,
                    "SET on_hand_quantity = on_hand_quantity - ?, reserved_quantity = reserved_quantity - ?", 2);
            try {
                reservations.markClaimed(shopId, reservationId, paymentReference, now);
            } catch (RuntimeException e) {
                if (SqlErrors.isDuplicateKey(e, ReservationRepository.PAYMENT_INDEX)) {
                    throw new ApiException(ErrorCode.PAYMENT_REFERENCE_CONFLICT,
                            "Payment reference is already used by another reservation.");
                }
                throw e;
            }
            return null;
        });
    }

    public Reservation cancel(long shopId, UUID reservationId) {
        return transition(shopId, reservationId, () -> {
            Reservation.Header header = lock(shopId, reservationId);
            if (header.status() == ReservationStatus.CANCELLED) {
                return null;
            }
            requireActive(header);
            updateCounters(shopId, reservationId, "SET reserved_quantity = reserved_quantity - ?", 1);
            reservations.markCancelled(shopId, reservationId, reservations.databaseNow());
            return null;
        });
    }

    /** 与主方案相同的到期释放语义：每个候选一个短事务，持头锁后复查。 */
    @Scheduled(fixedDelayString = "${stockhold.expiry.interval-millis:1000}")
    public void expireDue() {
        List<ExpiryCandidate> candidates;
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.BENCHMARK)) {
            candidates = reservations.findExpiryCandidates(null, 200);
        }
        for (ExpiryCandidate candidate : candidates) {
            try {
                transactions.execute(BusinessOperation.BENCHMARK, () -> {
                    Reservation.Header header = reservations
                            .lockHeader(candidate.shopId(), candidate.reservationId()).orElse(null);
                    Instant now = reservations.databaseNow();
                    if (header != null && header.status() == ReservationStatus.ACTIVE
                            && !header.expiresAt().isAfter(now)) {
                        updateCounters(candidate.shopId(), candidate.reservationId(),
                                "SET reserved_quantity = reserved_quantity - ?", 1);
                        reservations.markExpired(candidate.shopId(), candidate.reservationId(), now);
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                if (!SqlErrors.isTransientLockFailure(e)) {
                    throw e;
                }
            }
        }
    }

    private Reservation transition(long shopId, UUID reservationId, Supplier<Void> body) {
        transactions.executeWithRetry(BusinessOperation.BENCHMARK, body);
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.BENCHMARK)) {
            return reservations.find(shopId, reservationId).orElseThrow(ApiException::reservationNotFound);
        }
    }

    private Reservation.Header lock(long shopId, UUID reservationId) {
        return reservations.lockHeader(shopId, reservationId).orElseThrow(ApiException::reservationNotFound);
    }

    private static void requireActive(Reservation.Header header) {
        switch (header.status()) {
            case EXPIRED -> throw new ApiException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired.");
            case CLAIMED, CANCELLED -> throw new ApiException(ErrorCode.INVALID_RESERVATION_STATE,
                    "Reservation is already " + header.status().name().toLowerCase() + ".");
            case ACTIVE -> {
            }
        }
    }

    /** 按明细顺序（即 InventoryKey 升序）更新计数器行；quantityParams 是 SET 子句中数量占位符的个数。 */
    private void updateCounters(long shopId, UUID reservationId, String setClause, int quantityParams) {
        for (Reservation.Line line : reservations.findLines(shopId, reservationId)) {
            Object[] args = new Object[quantityParams + 4];
            Arrays.fill(args, 0, quantityParams, line.quantity());
            args[quantityParams] = shopId;
            args[quantityParams + 1] = line.inventoryItemId();
            args[quantityParams + 2] = line.locationId();
            args[quantityParams + 3] = line.quantity();
            int updated = jdbc.update(tag("UPDATE baseline_inventory_counter " + setClause + KEY_PREDICATE
                    + " AND reserved_quantity >= ?"), args);
            if (updated != 1) {
                throw new ApiException(ErrorCode.INVENTORY_INVARIANT_VIOLATION,
                        "Baseline counter cannot cover a reservation line.",
                        Map.of("inventoryItemId", line.inventoryItemId(), "locationId", line.locationId()));
            }
        }
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
}
