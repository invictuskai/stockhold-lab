package lab.inventory.domain;

import java.time.Instant;

/** 单条 SQL 取得的一致性快照：H、A、P、R 及容量。 */
public record InventorySnapshot(
        InventoryKey key,
        long onHand,
        long allocated,
        int poolCapacity,
        long poolAvailable,
        long activeReserved,
        Instant observedAt) {

    public long unallocated() {
        return onHand - allocated;
    }

    public long effectiveAvailable() {
        return onHand - activeReserved;
    }
}
