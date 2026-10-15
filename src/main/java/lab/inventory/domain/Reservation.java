package lab.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 预留头及其不可变明细。 */
public record Reservation(Header header, List<Line> lines) {

    public record Header(
            long shopId,
            UUID reservationId,
            String idempotencyKey,
            byte[] requestHash,
            ReservationStatus status,
            int ttlSeconds,
            int totalQuantity,
            int lineCount,
            String paymentReference,
            Instant expiresAt,
            Instant claimedAt,
            Instant cancelledAt,
            Instant expiredAt,
            Instant createdAt) {
    }

    public record Line(int lineNo, long inventoryItemId, long locationId, int quantity) {

        public InventoryKey key(long shopId) {
            return new InventoryKey(shopId, inventoryItemId, locationId);
        }
    }
}
