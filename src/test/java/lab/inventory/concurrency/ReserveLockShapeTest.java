package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import lab.inventory.application.FaultInjector.Point;
import lab.inventory.domain.InventoryKey;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.LockObserver;
import lab.inventory.support.LockObserver.Lock;
import org.junit.jupiter.api.Test;

/**
 * 文章结论在真实 reserve 路径上的锁形态：复合主键 + READ COMMITTED 下，每领取一个单位
 * 只在 PRIMARY 上持有一把 X,REC_NOT_GAP 记录锁，没有间隙 / supremum 锁，也不碰账本行。
 * 在单位已删除、明细尚未写入的事务中点观察，此时领取相关的锁都已取得。
 */
class ReserveLockShapeTest extends IntegrationTestBase {

    @Test
    void reserveHoldsExactlyOneRecordLockPerClaimedUnitAndNoLedgerLock() throws Exception {
        long shopId = newShop();
        InventoryKey first = createInventory(shopId, ITEM_A, LOCATION_X, 100, 50);
        InventoryKey second = createInventory(shopId, ITEM_B, LOCATION_Y, 100, 50);
        refills.refillOneDimension(first, false);
        refills.refillOneDimension(second, false);

        AtomicReference<List<Lock>> observed = new AtomicReference<>();
        try (LockObserver observer = LockObserver.open()) {
            Thread self = Thread.currentThread();
            faults.install(point -> {
                if (point == Point.RESERVE_AFTER_UNIT_DELETE && Thread.currentThread() == self) {
                    // jdbc 绑定在当前 reserve 事务的连接上，因此 CONNECTION_ID() 就是被观察的会话。
                    long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
                    try {
                        observed.set(observer.locksOf(connectionId, schema));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
            reservations.reserve(shopId, newKey("lock-shape"), null, List.of(line(first, 3), line(second, 2)));
        }

        List<Lock> locks = observed.get();
        assertThat(locks).as("locks observed inside the reserve transaction").isNotNull();
        List<Lock> unitLocks = LockObserver.recordLocksOn(locks, "reservation_units");
        assertThat(unitLocks).as("one lock per claimed unit: %s", locks).hasSize(5);
        assertThat(unitLocks).allSatisfy(lock -> {
            assertThat(lock.index()).isEqualTo("PRIMARY");
            assertThat(lock.mode()).isEqualTo("X,REC_NOT_GAP");
            assertThat(lock.status()).isEqualTo("GRANTED");
        });
        assertThat(locks).as("no gap / next-key / supremum lock anywhere").noneMatch(Lock::coversGap);
        // 正常预留不取账本行锁：热点计数行不在高频路径上。
        assertThat(locks).as("reserve must not touch the ledger")
                .noneMatch(lock -> lock.table().equals("inventory_ledger"));

        assertQuantities(first, 100, 50, 47, 3);
        assertQuantities(second, 100, 50, 48, 2);
    }

    /** 维度锁定的顺序与输入顺序无关：两个维度的锁都在，数量与请求一致。 */
    @Test
    void lockShapeIsTheSameWhenCartLinesArriveInReverseOrder() throws Exception {
        long shopId = newShop();
        InventoryKey first = createInventory(shopId, ITEM_A, LOCATION_X, 10, 10);
        InventoryKey second = createInventory(shopId, ITEM_B, LOCATION_Y, 10, 10);
        refills.refillOneDimension(first, false);
        refills.refillOneDimension(second, false);

        List<Integer> counts = new ArrayList<>();
        try (LockObserver observer = LockObserver.open()) {
            Thread self = Thread.currentThread();
            faults.install(point -> {
                if (point == Point.RESERVE_AFTER_UNIT_DELETE && Thread.currentThread() == self) {
                    long connectionId = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                    String schema = jdbc.queryForObject("SELECT DATABASE()", String.class);
                    try {
                        counts.add(LockObserver.recordLocksOn(observer.locksOf(connectionId, schema),
                                "reservation_units").size());
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });
            reservations.reserve(shopId, newKey("lock-shape"), null, List.of(line(second, 4), line(first, 1)));
        }

        assertThat(counts).containsExactly(5);
        assertQuantities(first, 10, 10, 9, 1);
        assertQuantities(second, 10, 10, 6, 4);
    }
}
