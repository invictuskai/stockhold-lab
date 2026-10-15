package lab.inventory.persistence;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lab.inventory.domain.InventoryKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryLedgerRepository {

    private static final String KEY_PREDICATE = " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ?";

    public record LedgerRow(long onHand, long allocated, int poolCapacity) {

        public long unallocated() {
            return onHand - allocated;
        }
    }

    public record RefillCandidate(InventoryKey key, int poolCapacity, long poolCount) {
    }

    private final JdbcTemplate jdbc;

    public InventoryLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(InventoryKey key, long onHand, int poolCapacity) {
        jdbc.update(tag("INSERT INTO inventory_ledger"
                + " (shop_id, inventory_item_id, location_id, on_hand_quantity, allocated_quantity, pool_capacity)"
                + " VALUES (?, ?, ?, ?, 0, ?)"),
                key.shopId(), key.inventoryItemId(), key.locationId(), onHand, poolCapacity);
    }

    /** 获取账本行锁；补充、调整在此之后才能读取额度。 */
    public Optional<LedgerRow> lockForUpdate(InventoryKey key) {
        return queryRow("SELECT on_hand_quantity, allocated_quantity, pool_capacity FROM inventory_ledger"
                + KEY_PREDICATE + " FOR UPDATE", key);
    }

    /** 非锁定读取；池容量创建后固定，可在预留事务之前校验。 */
    public Optional<LedgerRow> find(InventoryKey key) {
        return queryRow("SELECT on_hand_quantity, allocated_quantity, pool_capacity FROM inventory_ledger"
                + KEY_PREDICATE, key);
    }

    /** 一次往返读取同一 shop 下多个维度的池容量；未初始化的维度不出现在结果中。 */
    public Map<InventoryKey, Integer> findCapacities(long shopId, List<InventoryKey> keys) {
        StringBuilder sql = new StringBuilder("SELECT inventory_item_id, location_id, pool_capacity"
                + " FROM inventory_ledger WHERE shop_id = ? AND (inventory_item_id, location_id) IN (");
        List<Object> args = new ArrayList<>(keys.size() * 2 + 1);
        args.add(shopId);
        for (int i = 0; i < keys.size(); i++) {
            sql.append(i == 0 ? "(?, ?)" : ", (?, ?)");
            args.add(keys.get(i).inventoryItemId());
            args.add(keys.get(i).locationId());
        }
        sql.append(')');
        Map<InventoryKey, Integer> capacities = new HashMap<>();
        jdbc.query(tag(sql.toString()),
                rs -> {
                    capacities.put(new InventoryKey(shopId, rs.getLong(1), rs.getLong(2)), rs.getInt(3));
                },
                args.toArray());
        return capacities;
    }

    private Optional<LedgerRow> queryRow(String sql, InventoryKey key) {
        List<LedgerRow> rows = jdbc.query(tag(sql),
                (rs, i) -> new LedgerRow(rs.getLong(1), rs.getLong(2), rs.getInt(3)),
                key.shopId(), key.inventoryItemId(), key.locationId());
        return rows.stream().findFirst();
    }

    /** 补充：A += n。调用方必须已持有该账本行锁。 */
    public int increaseAllocated(InventoryKey key, long units) {
        return jdbc.update(tag("UPDATE inventory_ledger SET allocated_quantity = allocated_quantity + ?"
                + KEY_PREDICATE + " AND allocated_quantity + ? <= on_hand_quantity"),
                units, key.shopId(), key.inventoryItemId(), key.locationId(), units);
    }

    /** 确认：H -= n，A -= n；条件不满足说明内部不变量已损坏。 */
    public int claim(InventoryKey key, long units) {
        return jdbc.update(tag("UPDATE inventory_ledger"
                + " SET on_hand_quantity = on_hand_quantity - ?, allocated_quantity = allocated_quantity - ?"
                + KEY_PREDICATE + " AND on_hand_quantity >= ? AND allocated_quantity >= ?"),
                units, units, key.shopId(), key.inventoryItemId(), key.locationId(), units, units);
    }

    /** 取消 / 到期：A -= n，H 不变；额度回到 F，由后续补充重新变为单位行。 */
    public int release(InventoryKey key, long units) {
        return jdbc.update(tag("UPDATE inventory_ledger SET allocated_quantity = allocated_quantity - ?"
                + KEY_PREDICATE + " AND allocated_quantity >= ?"),
                units, key.shopId(), key.inventoryItemId(), key.locationId(), units);
    }

    public int updateOnHand(InventoryKey key, long newOnHand) {
        return jdbc.update(tag("UPDATE inventory_ledger SET on_hand_quantity = ?"
                + KEY_PREDICATE + " AND allocated_quantity <= ?"),
                newOnHand, key.shopId(), key.inventoryItemId(), key.locationId(), newOnHand);
    }

    /**
     * keyset 分页扫描仍有未发放额度（F > 0）的维度，附带普通 COUNT 的池行数。
     * 结果只用于挑选候选，真正的补充量在账本锁下重新计算。
     */
    public List<RefillCandidate> scanRefillCandidates(InventoryKey after, int limit) {
        long shop = after == null ? 0 : after.shopId();
        long item = after == null ? 0 : after.inventoryItemId();
        long location = after == null ? 0 : after.locationId();
        return jdbc.query(tag("SELECT l.shop_id, l.inventory_item_id, l.location_id, l.pool_capacity,"
                + " (SELECT COUNT(*) FROM reservation_units u"
                + "   WHERE u.shop_id = l.shop_id AND u.inventory_item_id = l.inventory_item_id"
                + "     AND u.location_id = l.location_id) AS pool_count"
                + " FROM inventory_ledger l"
                + " WHERE (l.shop_id > ? OR (l.shop_id = ? AND (l.inventory_item_id > ?"
                + "        OR (l.inventory_item_id = ? AND l.location_id > ?))))"
                + "   AND l.on_hand_quantity > l.allocated_quantity"
                + " ORDER BY l.shop_id, l.inventory_item_id, l.location_id LIMIT ?"),
                (rs, i) -> new RefillCandidate(
                        new InventoryKey(rs.getLong(1), rs.getLong(2), rs.getLong(3)), rs.getInt(4), rs.getLong(5)),
                shop, shop, item, item, location, limit);
    }
}
