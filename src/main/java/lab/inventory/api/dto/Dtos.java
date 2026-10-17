package lab.inventory.api.dto;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import lab.inventory.application.InvariantService;
import lab.inventory.application.PoolRefillService.RefillResult;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.persistence.InventoryOperationRepository.OperationRow;

/** HTTP API 契约 v1 的请求与响应体。ID 为 JSON 整数，时间为保留微秒的 UTC ISO-8601 字符串。 */
public final class Dtos {

    private static final DateTimeFormatter MICROS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private Dtos() {
    }

    static String time(Instant instant) {
        return instant == null ? null : MICROS.format(instant);
    }

    public record ErrorResponse(String code, String message, boolean retryable, String requestId,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> details) {
    }

    public record CreateInventoryRequest(UUID operationId, Long inventoryItemId, Long locationId,
            Long initialQuantity, Integer poolCapacity) {
    }

    public record AdjustInventoryRequest(UUID operationId, Long deltaQuantity) {
    }

    public record OperationResponse(UUID operationId, long shopId, long inventoryItemId, long locationId,
            long onHandAfter, long allocatedAfter, int poolCapacityAfter) {

        public static OperationResponse of(OperationRow row) {
            return new OperationResponse(row.operationId(), row.key().shopId(), row.key().inventoryItemId(),
                    row.key().locationId(), row.onHandAfter(), row.allocatedAfter(), row.poolCapacityAfter());
        }
    }

    public record InventorySnapshotResponse(long shopId, long inventoryItemId, long locationId,
            long onHandQuantity, long allocatedQuantity, long poolAvailableQuantity, long activeReservedQuantity,
            long unallocatedQuantity, long effectiveAvailableQuantity, int poolCapacity, String observedAt) {

        public static InventorySnapshotResponse of(InventorySnapshot s) {
            return new InventorySnapshotResponse(s.key().shopId(), s.key().inventoryItemId(), s.key().locationId(),
                    s.onHand(), s.allocated(), s.poolAvailable(), s.activeReserved(), s.unallocated(),
                    s.effectiveAvailable(), s.poolCapacity(), time(s.observedAt()));
        }
    }

    public record RefillResponse(long inventoryItemId, long locationId, int insertedUnits, long observedPoolBefore,
            long allocatedAfter, int poolCapacity) {

        public static RefillResponse of(RefillResult r) {
            return new RefillResponse(r.key().inventoryItemId(), r.key().locationId(), r.insertedUnits(),
                    r.observedPoolBefore(), r.allocatedAfter(), r.poolCapacity());
        }
    }

    /** locationId 与 locationIds（按优先级排列的候选地点）二选一。 */
    public record ReserveLine(Long inventoryItemId, Long locationId, Long quantity, List<Long> locationIds) {
    }

    public record ReserveRequest(Integer ttlSeconds, List<ReserveLine> lines) {
    }

    public record ClaimRequest(String paymentReference) {
    }

    public record ReservationLineResponse(int lineNo, long inventoryItemId, long locationId, int quantity) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReservationResponse(UUID reservationId, long shopId, String status, int totalQuantity,
            String createdAt, String expiresAt, String paymentReference, String claimedAt, String cancelledAt,
            String expiredAt, List<ReservationLineResponse> lines) {

        public static ReservationResponse of(Reservation reservation) {
            Reservation.Header h = reservation.header();
            return new ReservationResponse(h.reservationId(), h.shopId(), h.status().name(), h.totalQuantity(),
                    time(h.createdAt()), time(h.expiresAt()), h.paymentReference(), time(h.claimedAt()),
                    time(h.cancelledAt()), time(h.expiredAt()),
                    reservation.lines().stream()
                            .map(l -> new ReservationLineResponse(l.lineNo(), l.inventoryItemId(), l.locationId(),
                                    l.quantity()))
                            .toList());
        }
    }

    public record InvariantCheckRequest(Long shopId, Long inventoryItemId, Long locationId) {
    }

    public record InvariantCheckResponse(boolean valid, long onHandQuantity, long allocatedQuantity,
            long poolAvailableQuantity, long activeReservedQuantity, List<String> violations) {

        public static InvariantCheckResponse of(InvariantService.Report report) {
            InventorySnapshot s = report.snapshot();
            return new InvariantCheckResponse(report.valid(), s.onHand(), s.allocated(), s.poolAvailable(),
                    s.activeReserved(), report.violations());
        }
    }
}
