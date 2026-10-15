package lab.inventory.persistence;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 库存快照与审计。每个方法只发一条 SQL：RC 下单条语句使用同一个读视图，
 * 各聚合用独立标量子查询，避免两张一对多表 JOIN 后 SUM 造成乘法重复。
 */
@Repository
public class InventorySnapshotRepository {

    private static final String SNAPSHOT_COLUMNS = "SELECT l.on_hand_quantity, l.allocated_quantity, l.pool_capacity,"
            + " (SELECT COUNT(*) FROM reservation_units u"
            + "   WHERE u.shop_id = l.shop_id AND u.inventory_item_id = l.inventory_item_id"
            + "     AND u.location_id = l.location_id) AS pool_available_quantity,"
            + " (SELECT COALESCE(SUM(q.quantity), 0) FROM reserved_quantities q"
            + "   JOIN reservations r ON r.shop_id = q.shop_id AND r.reservation_id = q.reservation_id"
            + "   WHERE q.shop_id = l.shop_id AND q.inventory_item_id = l.inventory_item_id"
            + "     AND q.location_id = l.location_id AND r.status = 'ACTIVE') AS active_reserved_quantity,"
            + " UTC_TIMESTAMP(6) AS observed_at";

    private static final String AUDIT_COLUMNS = ","
            + " (SELECT COALESCE(SUM(q.quantity), 0) FROM reserved_quantities q"
            + "   JOIN reservations r ON r.shop_id = q.shop_id AND r.reservation_id = q.reservation_id"
            + "   WHERE q.shop_id = l.shop_id AND q.inventory_item_id = l.inventory_item_id"
            + "     AND q.location_id = l.location_id AND r.status = 'CLAIMED') AS claimed_quantity,"
            + " (SELECT COUNT(*) FROM reserved_quantities q"
            + "   LEFT JOIN reservations r ON r.shop_id = q.shop_id AND r.reservation_id = q.reservation_id"
            + "   WHERE q.shop_id = l.shop_id AND q.inventory_item_id = l.inventory_item_id"
            + "     AND q.location_id = l.location_id AND r.reservation_id IS NULL) AS orphan_lines,"
            + " (SELECT COALESCE(SUM(o.delta_quantity), 0) FROM inventory_operations o"
            + "   WHERE o.shop_id = l.shop_id AND o.inventory_item_id = l.inventory_item_id"
            + "     AND o.location_id = l.location_id) AS operation_delta";

    private static final String FROM_LEDGER = " FROM inventory_ledger l"
            + " WHERE l.shop_id = ? AND l.inventory_item_id = ? AND l.location_id = ?";

    /** 审计用的附加聚合：CLAIMED 明细总量、孤儿明细数、管理操作 delta 之和。 */
    public record Audit(InventorySnapshot snapshot, long claimedQuantity, long orphanLines, long operationDelta) {
    }

    private final JdbcTemplate jdbc;

    public InventorySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<InventorySnapshot> snapshot(InventoryKey key) {
        List<InventorySnapshot> rows = jdbc.query(tag(SNAPSHOT_COLUMNS + FROM_LEDGER),
                (rs, i) -> mapSnapshot(key, rs),
                key.shopId(), key.inventoryItemId(), key.locationId());
        return rows.stream().findFirst();
    }

    public Optional<Audit> audit(InventoryKey key) {
        List<Audit> rows = jdbc.query(tag(SNAPSHOT_COLUMNS + AUDIT_COLUMNS + FROM_LEDGER),
                (rs, i) -> new Audit(mapSnapshot(key, rs), rs.getLong("claimed_quantity"),
                        rs.getLong("orphan_lines"), rs.getLong("operation_delta")),
                key.shopId(), key.inventoryItemId(), key.locationId());
        return rows.stream().findFirst();
    }

    private static InventorySnapshot mapSnapshot(InventoryKey key, ResultSet rs) throws SQLException {
        return new InventorySnapshot(key,
                rs.getLong("on_hand_quantity"),
                rs.getLong("allocated_quantity"),
                rs.getInt("pool_capacity"),
                rs.getLong("pool_available_quantity"),
                rs.getLong("active_reserved_quantity"),
                Codec.instant(rs, "observed_at"));
    }
}
