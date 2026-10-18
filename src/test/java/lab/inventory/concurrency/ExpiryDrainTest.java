package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.persistence.Codec;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.MySqlTestDatabase;
import org.junit.jupiter.api.Test;

/**
 * SKIP LOCKED 到期清理：多个“实例”同时清理时每个预留只释放一次；被别的事务持有的头直接跳过而不等待，
 * 其余积压照常清理。
 */
class ExpiryDrainTest extends IntegrationTestBase {

    /** 到期清理是全局的：先清掉同一 JVM 里其他测试留下的到期积压，本测试的计数才精确。 */
    @org.junit.jupiter.api.BeforeEach
    void drainForeignBacklog() {
        while (expiry.drain() > 0) {
            // 继续
        }
    }

    private long overdue(long shopId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE shop_id = ? AND status = 'ACTIVE'"
                + " AND expires_at <= UTC_TIMESTAMP(6)", Long.class, shopId);
    }

    @Test
    void drainReleasesOnlyDueReservations() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 30, 1000);
        Reservation due = reserve(key, 4);
        Reservation notDue = reserve(key, 6);
        forceExpired(due);

        while (expiry.drain() > 0) {
            // 继续直到没有可释放的
        }

        assertThat(reservations.get(key.shopId(), due.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.get(key.shopId(), notDue.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(key, 30, 26, 20, 6);
    }

    @Test
    void concurrentDrainsReleaseEachReservationExactlyOnce() throws InterruptedException {
        long shopId = newShop();
        List<InventoryKey> keys = List.of(createInventory(shopId, ITEM_A, LOCATION_X, 1000, 1000),
                createInventory(shopId, ITEM_B, LOCATION_X, 1000, 1000));
        int perKey = 120;
        for (InventoryKey key : keys) {
            for (int i = 0; i < perKey; i++) {
                forceExpired(reserve(key, 1));
            }
        }

        // 四个并发清理代表四个应用实例，每个实例内部还有自己的工作线程。
        List<Callable<Integer>> instances = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            instances.add(() -> {
                int released = 0;
                int round;
                while ((round = expiry.drain()) > 0) {
                    released += round;
                }
                return released;
            });
        }
        List<Outcome<Integer>> outcomes = runConcurrently(instances);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().mapToInt(Outcome::value).sum()).isEqualTo(keys.size() * perKey);
        assertThat(overdue(shopId)).isZero();
        for (InventoryKey key : keys) {
            assertQuantities(key, 1000, 1000 - perKey, 1000 - perKey, 0);
        }
    }

    /** 一批里有多店铺、多维度、多明细的预留：账本按维度汇总后一次扣回，各维度的数量都必须精确。 */
    @Test
    void batchReleaseAggregatesPerDimensionAcrossShopsAndLines() {
        long shopA = newShop();
        long shopB = newShop();
        InventoryKey a1 = createInventory(shopA, ITEM_A, LOCATION_X, 100, 1000);
        InventoryKey a2 = createInventory(shopA, ITEM_B, LOCATION_X, 100, 1000);
        InventoryKey b1 = createInventory(shopB, ITEM_A, LOCATION_X, 100, 1000);
        for (int i = 0; i < 10; i++) {
            forceExpired(reservations.reserve(shopA, newKey("multi"), null,
                    List.of(line(a1, 2), line(a2, 3))).reservation());
            forceExpired(reserve(b1, 4));
        }

        while (expiry.drain() > 0) {
            // 继续
        }

        assertQuantities(a1, 100, 80, 80, 0);
        assertQuantities(a2, 100, 70, 70, 0);
        assertQuantities(b1, 100, 60, 60, 0);
    }

    @Test
    void faultAfterTheLedgerUpdateRollsBackTheWholeBatch() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        for (int i = 0; i < 6; i++) {
            forceExpired(reserve(key, 1));
        }
        faults.install(point -> {
            if (point == lab.inventory.application.FaultInjector.Point.RELEASE_AFTER_LEDGER_UPDATE) {
                throw new IllegalStateException("injected");
            }
        });
        try {
            assertThat(expiry.drain()).isZero();
        } finally {
            faults.clear();
        }
        assertQuantities(key, 100, 100, 94, 6);
        assertThat(overdue(key.shopId())).isEqualTo(6);

        assertThat(expiry.drain()).isEqualTo(6);
        assertQuantities(key, 100, 94, 94, 0);
    }

    @Test
    void headHeldByAnotherTransactionIsSkippedWithoutWaiting() throws Exception {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        Reservation held = reserve(key, 1);
        List<Reservation> others = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            others.add(reserve(key, 1));
        }
        forceExpired(held);
        others.forEach(this::forceExpired);

        MySqlTestDatabase.Coordinates db = MySqlTestDatabase.resolve();
        try (Connection session = DriverManager.getConnection(db.url(), db.username(), db.password())) {
            session.setAutoCommit(false);
            try (PreparedStatement lock = session.prepareStatement(
                    "SELECT status FROM reservations WHERE shop_id = ? AND reservation_id = ? FOR UPDATE")) {
                lock.setLong(1, key.shopId());
                lock.setBytes(2, Codec.uuidToBytes(held.header().reservationId()));
                lock.executeQuery().close();
            }

            long start = System.nanoTime();
            int released = expiry.drain();
            long millis = (System.nanoTime() - start) / 1_000_000;

            // 不等那一把锁（锁等待超时是 1 秒），其余 5 个照常释放。
            assertThat(released).isEqualTo(5);
            assertThat(millis).as("drain did not wait for the held header").isLessThan(900);
            assertThat(overdue(key.shopId())).isEqualTo(1);
            session.rollback();
        }

        assertThat(expiry.drain()).isEqualTo(1);
        assertQuantities(key, 100, 94, 94, 0);
    }
}
