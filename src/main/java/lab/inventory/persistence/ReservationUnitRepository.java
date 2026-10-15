package lab.inventory.persistence;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.util.ArrayList;
import java.util.List;

import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationUnitRepository {

    private static final String KEY_PREDICATE = " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ?";

    private final JdbcTemplate jdbc;

    public ReservationUnitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 锁定最多 limit 个未被其他事务锁住的单位行；返回行数不足不代表售罄。 */
    public List<byte[]> lockAvailable(InventoryKey key, int limit) {
        return jdbc.query(tag("SELECT unit_id FROM reservation_units" + KEY_PREDICATE
                + " ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED"),
                (rs, i) -> rs.getBytes(1),
                key.shopId(), key.inventoryItemId(), key.locationId(), limit);
    }

    /**
     * 候选优化（总体技术方案 8.2）：用 UNION ALL 在一次往返中锁定多个维度的单位。
     * 每个分支自带 FOR UPDATE SKIP LOCKED；外层加锁子句不能替代分支内加锁。
     * 返回的列表与 lines 一一对应；不依赖优化器按文本顺序取得各分支的锁。
     */
    public List<List<byte[]>> lockAvailableUnionAll(List<CanonicalReservationRequest.Line> lines) {
        StringBuilder sql = new StringBuilder();
        List<Object> args = new ArrayList<>(lines.size() * 5);
        for (int i = 0; i < lines.size(); i++) {
            InventoryKey key = lines.get(i).key();
            sql.append(i == 0 ? "(" : " UNION ALL (")
                    .append("SELECT ? AS branch_no, unit_id FROM reservation_units").append(KEY_PREDICATE)
                    .append(" ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED)");
            args.add(i);
            args.add(key.shopId());
            args.add(key.inventoryItemId());
            args.add(key.locationId());
            args.add(lines.get(i).quantity());
        }
        List<List<byte[]>> locked = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            locked.add(new ArrayList<>(lines.get(i).quantity()));
        }
        jdbc.query(tag(sql.toString()),
                rs -> {
                    locked.get(rs.getInt(1)).add(rs.getBytes(2));
                },
                args.toArray());
        return locked;
    }

    /** 按刚才锁定的精确 ID 删除；不使用独立的 DELETE ... LIMIT n。 */
    public int deleteExact(InventoryKey key, List<byte[]> unitIds) {
        StringBuilder sql = new StringBuilder("DELETE FROM reservation_units").append(KEY_PREDICATE)
                .append(" AND unit_id IN (");
        List<Object> args = new ArrayList<>(unitIds.size() + 3);
        args.add(key.shopId());
        args.add(key.inventoryItemId());
        args.add(key.locationId());
        for (int i = 0; i < unitIds.size(); i++) {
            sql.append(i == 0 ? "?" : ", ?");
            args.add(unitIds.get(i));
        }
        sql.append(')');
        return jdbc.update(tag(sql.toString()), args.toArray());
    }

    /** 普通一致性读计数，不加锁；未提交的删除仍被计入，只可能偏高。 */
    public long count(InventoryKey key) {
        Long count = jdbc.queryForObject(tag("SELECT COUNT(*) FROM reservation_units" + KEY_PREDICATE),
                Long.class, key.shopId(), key.inventoryItemId(), key.locationId());
        return count == null ? 0 : count;
    }

    /** 多行 INSERT 一个批次；不使用 INSERT IGNORE，返回实际插入行数供调用方断言。 */
    public int insertBatch(InventoryKey key, List<byte[]> sortedUnitIds) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO reservation_units (shop_id, inventory_item_id, location_id, unit_id) VALUES ");
        List<Object> args = new ArrayList<>(sortedUnitIds.size() * 4);
        for (int i = 0; i < sortedUnitIds.size(); i++) {
            sql.append(i == 0 ? "(?, ?, ?, ?)" : ", (?, ?, ?, ?)");
            args.add(key.shopId());
            args.add(key.inventoryItemId());
            args.add(key.locationId());
            args.add(sortedUnitIds.get(i));
        }
        return jdbc.update(tag(sql.toString()), args.toArray());
    }
}
