package lab.inventory.persistence;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lab.inventory.domain.InventoryKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryOperationRepository {

    public static final String TYPE_INITIALIZE = "INITIALIZE";
    public static final String TYPE_ADJUST = "ADJUST";

    /** after 字段是当次操作提交时的快照，不是当前库存。 */
    public record OperationRow(UUID operationId, InventoryKey key, byte[] requestHash, String type,
            long deltaQuantity, long onHandAfter, long allocatedAfter, int poolCapacityAfter) {
    }

    private final JdbcTemplate jdbc;

    public InventoryOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OperationRow row) {
        jdbc.update(tag("INSERT INTO inventory_operations"
                + " (shop_id, operation_id, request_hash, operation_type, inventory_item_id, location_id,"
                + "  delta_quantity, on_hand_after, allocated_after, pool_capacity_after)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"),
                row.key().shopId(), Codec.uuidToBytes(row.operationId()), row.requestHash(), row.type(),
                row.key().inventoryItemId(), row.key().locationId(),
                row.deltaQuantity(), row.onHandAfter(), row.allocatedAfter(), row.poolCapacityAfter());
    }

    public void updateSnapshot(long shopId, UUID operationId, long onHandAfter, long allocatedAfter,
            int poolCapacityAfter) {
        jdbc.update(tag("UPDATE inventory_operations"
                + " SET on_hand_after = ?, allocated_after = ?, pool_capacity_after = ?"
                + " WHERE shop_id = ? AND operation_id = ?"),
                onHandAfter, allocatedAfter, poolCapacityAfter, shopId, Codec.uuidToBytes(operationId));
    }

    public Optional<OperationRow> find(long shopId, UUID operationId) {
        List<OperationRow> rows = jdbc.query(tag("SELECT shop_id, operation_id, request_hash, operation_type,"
                + " inventory_item_id, location_id, delta_quantity, on_hand_after, allocated_after,"
                + " pool_capacity_after FROM inventory_operations WHERE shop_id = ? AND operation_id = ?"),
                (rs, i) -> new OperationRow(
                        Codec.uuidFromBytes(rs.getBytes(2)),
                        new InventoryKey(rs.getLong(1), rs.getLong(5), rs.getLong(6)),
                        rs.getBytes(3), rs.getString(4), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getInt(10)),
                shopId, Codec.uuidToBytes(operationId));
        return rows.stream().findFirst();
    }
}
