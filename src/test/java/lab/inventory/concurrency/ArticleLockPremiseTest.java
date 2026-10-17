package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import lab.inventory.persistence.Codec;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.LockObserver;
import lab.inventory.support.LockObserver.Lock;
import lab.inventory.support.MySqlTestDatabase;
import org.junit.jupiter.api.Test;

/**
 * 复现文章中两个促成设计决策的观察，用原生 JDBC 会话精确控制隔离级别和事务边界：
 * <ol>
 * <li>自增主键 + 维度二级索引时，每领取一行要锁两次（二级索引 + 聚簇索引）；复合主键只锁一次。</li>
 * <li>REPEATABLE READ 下对空池执行 SKIP LOCKED 会持有间隙 / supremum 锁，挡住补充的 INSERT；
 * READ COMMITTED 下不会。</li>
 * </ol>
 */
class ArticleLockPremiseTest extends IntegrationTestBase {

    private static final String UNIT_SQL = "SELECT unit_id FROM reservation_units"
            + " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ?"
            + " ORDER BY unit_id LIMIT ? FOR UPDATE SKIP LOCKED";

    /** 文章原型的建模方式；只在本测试中使用，不属于正式迁移。 */
    private static final String PROTOTYPE_DDL = "CREATE TABLE IF NOT EXISTS article_prototype_units ("
            + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
            + " shop_id BIGINT NOT NULL, inventory_item_id BIGINT NOT NULL, location_id BIGINT NOT NULL,"
            + " KEY idx_prototype_dimension (shop_id, inventory_item_id, location_id)) ENGINE=InnoDB";

    @Test
    void autoIncrementPrototypeLocksTwicePerRowWhileCompositeKeyLocksOnce() throws Exception {
        long shopId = newShop();
        jdbc.execute(PROTOTYPE_DDL);
        // 其他维度的行让二级索引具有选择性，与生产大表上优化器的选择一致。
        for (long item = ITEM_A; item < ITEM_A + 20; item++) {
            for (int i = 0; i < 10; i++) {
                jdbc.update("INSERT INTO article_prototype_units (shop_id, inventory_item_id, location_id)"
                        + " VALUES (?, ?, ?)", shopId, item, LOCATION_X);
            }
        }
        var key = createInventory(shopId, ITEM_A, LOCATION_X, 10, 10);
        refills.refillOneDimension(key, false);

        try (LockObserver observer = LockObserver.open(); Connection session = openSession()) {
            session.setAutoCommit(false);
            try (PreparedStatement select = session.prepareStatement("SELECT id FROM article_prototype_units"
                    + " FORCE INDEX (idx_prototype_dimension)"
                    + " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ?"
                    + " ORDER BY id LIMIT 3 FOR UPDATE SKIP LOCKED")) {
                bindDimension(select, shopId, ITEM_A, LOCATION_X);
                assertThat(countRows(select)).isEqualTo(3);
            }
            List<Lock> prototype = LockObserver.recordLocksOn(observe(observer, session), "article_prototype_units");
            assertThat(prototype).as("prototype locks: %s", prototype).hasSize(6);
            assertThat(prototype).filteredOn(l -> l.index().equals("idx_prototype_dimension")).hasSize(3);
            assertThat(prototype).filteredOn(l -> l.index().equals("PRIMARY")).hasSize(3);
            session.rollback();

            try (PreparedStatement select = session.prepareStatement(UNIT_SQL)) {
                bindDimension(select, shopId, ITEM_A, LOCATION_X);
                select.setInt(4, 3);
                assertThat(countRows(select)).isEqualTo(3);
            }
            List<Lock> composite = LockObserver.recordLocksOn(observe(observer, session), "reservation_units");
            assertThat(composite).as("composite key locks: %s", composite).hasSize(3)
                    .allMatch(l -> l.index().equals("PRIMARY") && l.mode().equals("X,REC_NOT_GAP"));
            session.rollback();
        }
    }

    @Test
    void repeatableReadSkipLockedOnEmptyPoolBlocksRefillInsert() throws Exception {
        long shopId = newShop();
        try (LockObserver observer = LockObserver.open();
                Connection reserver = openSession();
                Connection refiller = openSession()) {
            reserver.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            reserver.setAutoCommit(false);
            assertThat(selectEmptyPool(reserver, shopId)).isZero();

            List<Lock> held = LockObserver.recordLocksOn(observe(observer, reserver), "reservation_units");
            assertThat(held).as("RR keeps a gap / next-key / supremum lock: %s", held).anyMatch(Lock::coversGap);

            refiller.setAutoCommit(false);
            SQLException blocked = catchThrowableOfType(SQLException.class, () -> insertUnit(refiller, shopId));
            assertThat((Throwable) blocked).as("refill insert must wait on the gap lock").isNotNull();
            assertThat(blocked.getErrorCode()).as("lock wait timeout").isEqualTo(1205);
            refiller.rollback();
            reserver.rollback();
        }
    }

    @Test
    void readCommittedSkipLockedOnEmptyPoolLeavesRefillInsertFree() throws Exception {
        long shopId = newShop();
        try (LockObserver observer = LockObserver.open();
                Connection reserver = openSession();
                Connection refiller = openSession()) {
            reserver.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            reserver.setAutoCommit(false);
            assertThat(selectEmptyPool(reserver, shopId)).isZero();

            assertThat(LockObserver.recordLocksOn(observe(observer, reserver), "reservation_units"))
                    .as("RC holds no record lock for an empty range").isEmpty();

            refiller.setAutoCommit(false);
            assertThat(insertUnit(refiller, shopId)).isEqualTo(1);
            refiller.rollback();
            reserver.rollback();
        }
    }

    /**
     * 文章：RR 下空池上的 SKIP LOCKED 持有间隙锁，“阻塞补充插入并可能导致死锁”。两个预留同时扫描同一个空池时，
     * 各自的间隙锁互相兼容；随后两边都插入（补充）时，插入意向锁互相等待对方的间隙锁，形成死锁。
     */
    @Test
    void repeatableReadGapLocksDeadlockTwoConcurrentRefillInserts() throws Exception {
        assertThat(gapLockRaceErrorCodes(Connection.TRANSACTION_REPEATABLE_READ, newShop())).contains(1213);
    }

    @Test
    void readCommittedHasNoGapLockSoConcurrentRefillInsertsBothSucceed() throws Exception {
        assertThat(gapLockRaceErrorCodes(Connection.TRANSACTION_READ_COMMITTED, newShop())).containsOnly(0);
    }

    /** 两个会话先在同一个空池上做锁定读，再各自插入；返回两次插入的错误码（0 表示成功）。 */
    private static List<Integer> gapLockRaceErrorCodes(int isolation, long shopId) throws Exception {
        try (Connection first = openSession(); Connection second = openSession()) {
            for (Connection session : List.of(first, second)) {
                session.setTransactionIsolation(isolation);
                session.setAutoCommit(false);
                assertThat(selectEmptyPool(session, shopId)).isZero();
            }
            java.util.concurrent.CompletableFuture<Integer> firstInsert =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> errorCode(() -> insertUnit(first, shopId)));
            Thread.sleep(200); // 让第一个插入先进入等待（RR）或完成（RC）
            int secondCode = errorCode(() -> insertUnit(second, shopId));
            int firstCode = firstInsert.get(10, java.util.concurrent.TimeUnit.SECONDS);
            first.rollback();
            second.rollback();
            return List.of(firstCode, secondCode);
        }
    }

    /**
     * 文章的锁顺序问题：一条路径先写 reserved_quantities 再删 reservation_units，另一条路径反过来，
     * 两者争同一行明细和同一个单位时形成循环等待；统一为“先单位、后明细”后只会排队，不会死锁。
     */
    @Test
    void oppositeTableOrderDeadlocksWhileTheArticlesOrderOnlyWaits() throws Exception {
        assertThat(tableOrderRace(true)).contains(1213);
        assertThat(tableOrderRace(false)).doesNotContain(1213);
    }

    /** 返回两个会话第二步的错误码；opposite 为 true 时第一个会话按文章修正前的顺序（先明细后单位）执行。 */
    private List<Integer> tableOrderRace(boolean opposite) throws Exception {
        long shopId = newShop();
        var key = createInventory(shopId, ITEM_A, LOCATION_X, 1, 1);
        refills.refillOneDimension(key, false);
        byte[] unit = jdbc.queryForObject("SELECT unit_id FROM reservation_units WHERE shop_id = ?", byte[].class,
                shopId);
        byte[] reservationId = Codec.uuidToBytes(UUID.randomUUID());
        try (Connection first = openSession(); Connection second = openSession()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            Step deleteUnitFirst = () -> deleteUnit(first, shopId, unit);
            Step insertDetailFirst = () -> insertDetail(first, shopId, reservationId);
            // 第一个会话先完成一步：修正前的顺序先写明细，修正后的顺序先删单位。
            (opposite ? insertDetailFirst : deleteUnitFirst).run();
            // 第二个会话按修正后的顺序：先删单位。修正后它会在这里等待第一个会话，因此放到后台执行。
            java.util.concurrent.CompletableFuture<Integer> secondPath =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> errorCode(() -> {
                        deleteUnit(second, shopId, unit);
                        insertDetail(second, shopId, reservationId);
                        return 0;
                    }));
            Thread.sleep(200);
            int firstCode = errorCode(() -> {
                (opposite ? deleteUnitFirst : insertDetailFirst).run();
                return 0;
            });
            if (firstCode == 0) {
                first.commit();
            } else {
                first.rollback();
            }
            int secondCode = secondPath.get(10, java.util.concurrent.TimeUnit.SECONDS);
            second.rollback();
            return List.of(firstCode, secondCode);
        }
    }

    @FunctionalInterface
    private interface Step {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlCall {
        int call() throws SQLException;
    }

    private static int errorCode(SqlCall call) {
        try {
            call.call();
            return 0;
        } catch (SQLException e) {
            return e.getErrorCode();
        }
    }

    private static void deleteUnit(Connection session, long shopId, byte[] unit) throws SQLException {
        try (PreparedStatement delete = session.prepareStatement("DELETE FROM reservation_units"
                + " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ? AND unit_id = ?")) {
            bindDimension(delete, shopId, ITEM_A, LOCATION_X);
            delete.setBytes(4, unit);
            delete.executeUpdate();
        }
    }

    private static void insertDetail(Connection session, long shopId, byte[] reservationId) throws SQLException {
        try (PreparedStatement insert = session.prepareStatement("INSERT INTO reserved_quantities"
                + " (shop_id, reservation_id, line_no, inventory_item_id, location_id, quantity)"
                + " VALUES (?, ?, 1, ?, ?, 1)")) {
            insert.setLong(1, shopId);
            insert.setBytes(2, reservationId);
            insert.setLong(3, ITEM_A);
            insert.setLong(4, LOCATION_X);
            insert.executeUpdate();
        }
    }

    private static Connection openSession() throws SQLException {
        MySqlTestDatabase.Coordinates db = MySqlTestDatabase.resolve();
        return DriverManager.getConnection(db.url(), db.username(), db.password());
    }

    private static List<Lock> observe(LockObserver observer, Connection session) throws SQLException {
        try (Statement statement = session.createStatement();
                ResultSet rs = statement.executeQuery("SELECT CONNECTION_ID(), DATABASE()")) {
            rs.next();
            return observer.locksOf(rs.getLong(1), rs.getString(2));
        }
    }

    private static int selectEmptyPool(Connection session, long shopId) throws SQLException {
        try (PreparedStatement select = session.prepareStatement(UNIT_SQL)) {
            bindDimension(select, shopId, ITEM_A, LOCATION_X);
            select.setInt(4, 3);
            return countRows(select);
        }
    }

    private static int insertUnit(Connection session, long shopId) throws SQLException {
        try (PreparedStatement insert = session.prepareStatement("INSERT INTO reservation_units"
                + " (shop_id, inventory_item_id, location_id, unit_id) VALUES (?, ?, ?, ?)")) {
            bindDimension(insert, shopId, ITEM_A, LOCATION_X);
            insert.setBytes(4, Codec.uuidToBytes(UUID.randomUUID()));
            return insert.executeUpdate();
        }
    }

    private static void bindDimension(PreparedStatement statement, long shopId, long itemId, long locationId)
            throws SQLException {
        statement.setLong(1, shopId);
        statement.setLong(2, itemId);
        statement.setLong(3, locationId);
    }

    private static int countRows(PreparedStatement select) throws SQLException {
        int rows = 0;
        try (ResultSet rs = select.executeQuery()) {
            while (rs.next()) {
                rows++;
            }
        }
        return rows;
    }
}
