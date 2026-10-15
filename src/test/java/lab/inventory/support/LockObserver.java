package lab.inventory.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;

/**
 * 用独立的观察账号读取 performance_schema.data_locks，查看某个业务连接此刻持有的 InnoDB 锁。
 * 观察连接不属于业务连接池，也不参与被观察的事务。
 */
public final class LockObserver implements AutoCloseable {

    /** data_locks 的一行；lockData 对 supremum 为 "supremum pseudo-record"。 */
    public record Lock(String table, String index, String type, String mode, String status, String lockData) {

        public boolean isRecord() {
            return "RECORD".equals(type);
        }

        /** 间隙锁、next-key 锁或 supremum 锁都会挡住同一区间的 INSERT。 */
        public boolean coversGap() {
            return isRecord() && (!mode.contains("REC_NOT_GAP") || "supremum pseudo-record".equals(lockData));
        }
    }

    private final Connection connection;

    private LockObserver(Connection connection) {
        this.connection = connection;
    }

    /** 没有配置观察账号时跳过调用方测试，并说明原因。 */
    public static LockObserver open() throws SQLException {
        MySqlTestDatabase.Coordinates observer = MySqlTestDatabase.observer().orElse(null);
        Assumptions.assumeTrue(observer != null,
                "Set STOCKHOLD_TEST_OBSERVER_USER / PASSWORD (SELECT on performance_schema) to observe data_locks");
        return new LockObserver(DriverManager.getConnection(observer.url(), observer.username(), observer.password()));
    }

    /** 指定连接（CONNECTION_ID()）在指定库中当前持有或等待的锁。 */
    public List<Lock> locksOf(long connectionId, String schema) throws SQLException {
        String sql = "SELECT l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_TYPE, l.LOCK_MODE, l.LOCK_STATUS, l.LOCK_DATA"
                + " FROM performance_schema.data_locks l"
                + " JOIN performance_schema.threads t ON t.THREAD_ID = l.THREAD_ID"
                + " WHERE t.PROCESSLIST_ID = ? AND l.OBJECT_SCHEMA = ?"
                + " ORDER BY l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_DATA";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, connectionId);
            statement.setString(2, schema);
            List<Lock> locks = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    locks.add(new Lock(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6)));
                }
            }
            return locks;
        }
    }

    public static List<Lock> recordLocksOn(List<Lock> locks, String table) {
        return locks.stream().filter(l -> l.isRecord() && l.table().equals(table)).toList();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
