package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lab.inventory.application.FaultInjector.Point;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 跨结账批量预留特有的行为：批次确实形成、一次事务服务多个结账、成员之间隔离、整批故障整体回滚。 */
@TestPropertySource(properties = {"stockhold.reserve.batching.enabled=true",
        "stockhold.reserve.batching.max-wait-micros=5000"})
class ReserveBatchingTest extends IntegrationTestBase {

    private static final class InjectedFault extends RuntimeException {
    }

    @Autowired
    private MeterRegistry meters;

    private DistributionSummary batchSizes() {
        return meters.find("stockhold.reserve.batch.size").summary();
    }

    private long reserveTransactions() {
        return meters.find("stockhold.transaction").tag("op", "reserve").timers().stream()
                .mapToLong(Timer::count).sum();
    }

    @Test
    void concurrentCheckoutsShareOneLockingQueryAndFewerTransactions() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 1000);
        refills.refillOneDimension(key, false);
        long transactionsBefore = reserveTransactions();
        double maxBefore = batchSizes().max();
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            tasks.add(() -> reserve(key, 1));
        }

        List<Outcome<Reservation>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(batchSizes().max()).as("largest batch").isGreaterThan(Math.max(1, maxBefore));
        assertThat(reserveTransactions() - transactionsBefore).as("reserve transactions for 64 checkouts")
                .isLessThan(64);
        assertQuantities(key, 1000, 1000, 936, 64);
    }

    @Test
    void duplicateKeyInsideABatchOnlyAffectsThatMember() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        refills.refillOneDimension(key, false);
        String shared = newKey("shared");
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String idempotencyKey = i % 6 == 0 ? shared : newKey("own");
            tasks.add(() -> reservations.reserve(key.shopId(), idempotencyKey, null, List.of(line(key, 1)))
                    .replayed());
        }

        List<Outcome<Boolean>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        // 4 个请求共用一个键：只创建 1 个预留，其余 3 个是重放；另外 20 个各自创建。
        assertThat(outcomes.stream().filter(o -> o.value()).count()).isEqualTo(3);
        assertQuantities(key, 100, 100, 79, 21);
    }

    private long reserveRollbacks() {
        return meters.find("stockhold.transaction").tag("op", "reserve").tag("result", "rollback").timers().stream()
                .mapToLong(Timer::count).sum();
    }

    /**
     * 另一个未提交的事务正持有幂等键 K：批次中 K 的写头要等锁（最多 innodb_lock_wait_timeout），
     * 超时只回滚 K 这个成员，同批的其他结账照常提交，批次事务不回滚。
     */
    @Test
    void keyHeldByAnotherTransactionOnlyAffectsThatMember() throws Exception {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        InventoryKey other = createInventory(key.shopId(), ITEM_B, LOCATION_X, 100, 1000);
        refills.refillOneDimension(key, false);
        refills.refillOneDimension(other, false);
        String held = newKey("held");
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        try {
            // 两维度的预留不走批量路径：写完头后停住，持有幂等键 K 的锁。
            var holder = pool.submit(() -> {
                Thread self = Thread.currentThread();
                faults.install(point -> {
                    if (point == Point.RESERVE_AFTER_UNIT_DELETE && Thread.currentThread() == self) {
                        holding.countDown();
                        try {
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                return reservations.reserve(key.shopId(), held, null, List.of(line(key, 1), line(other, 1)));
            });
            assertThat(holding.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long rollbacksBefore = reserveRollbacks();

            var stalled = pool.submit(() -> errorCodeOf(() ->
                    reservations.reserve(key.shopId(), held, null, List.of(line(key, 1)))));
            List<java.util.concurrent.Future<Reservation>> others = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                others.add(pool.submit(() -> reserve(key, 1)));
            }
            for (var future : others) {
                assertThat(future.get(10, java.util.concurrent.TimeUnit.SECONDS).header().status().name())
                        .isEqualTo("ACTIVE");
            }
            assertThat(reserveRollbacks() - rollbacksBefore).as("batch transactions rolled back").isZero();

            release.countDown();
            assertThat(holder.get(10, java.util.concurrent.TimeUnit.SECONDS).reservation().header().idempotencyKey())
                    .isEqualTo(held);
            // K 的单维度请求与持有者是不同请求：最终是幂等冲突，或在重试预算内仍被锁住而报繁忙。
            assertThat(stalled.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isIn(lab.inventory.domain.exception.ErrorCode.IDEMPOTENCY_CONFLICT,
                            lab.inventory.domain.exception.ErrorCode.INVENTORY_BUSY);
        } finally {
            release.countDown();
            faults.clear();
            pool.shutdownNow();
        }
        assertQuantities(key, 100, 100, 96, 4);
    }

    @Test
    void injectedFaultRollsBackTheWholeBatchAndLeavesNothingBehind() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        refills.refillOneDimension(key, false);
        faults.install(point -> {
            if (point == Point.RESERVE_AFTER_UNIT_DELETE) {
                throw new InjectedFault();
            }
        });
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> reserve(key, 2));
        }

        List<Outcome<Reservation>> outcomes = runConcurrently(tasks);
        faults.clear();

        assertThat(outcomes).allMatch(o -> o.error() instanceof InjectedFault);
        assertQuantities(key, 100, 100, 100, 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE shop_id = ?", Long.class,
                key.shopId())).isZero();
        reserve(key, 2);
        assertQuantities(key, 100, 100, 98, 2);
    }

    @Test
    void membersBeyondThePoolFallBackToRefillAndAllSucceed() throws InterruptedException {
        // 池只有 5 个单位，8 个结账同时到达：池内的先满足，其余缺行后补充再重试。
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 8, 5);
        refills.refillOneDimension(key, false);
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> reserve(key, 1));
        }

        List<Outcome<Reservation>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertQuantities(key, 8, 8, 0, 8);
    }
}
