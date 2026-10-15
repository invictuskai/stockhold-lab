package lab.inventory.domain;

import java.util.Comparator;

import lab.inventory.domain.exception.ApiException;

/** 库存维度 (shop, item, location)；自然顺序即全局统一的锁顺序。 */
public record InventoryKey(long shopId, long inventoryItemId, long locationId) implements Comparable<InventoryKey> {

    private static final Comparator<InventoryKey> ORDER = Comparator
            .comparingLong(InventoryKey::shopId)
            .thenComparingLong(InventoryKey::inventoryItemId)
            .thenComparingLong(InventoryKey::locationId);

    public InventoryKey {
        if (shopId <= 0 || inventoryItemId <= 0 || locationId <= 0) {
            throw ApiException.invalidArgument("shopId, inventoryItemId and locationId must be positive integers.");
        }
    }

    @Override
    public int compareTo(InventoryKey other) {
        return ORDER.compare(this, other);
    }
}
