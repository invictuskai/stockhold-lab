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
