package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.job.PoolRefillJob;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JobsAndObservabilityTest extends IntegrationTestBase {

    @Autowired
    private InventoryLedgerRepository ledgers;
    @Autowired
    private StockholdProperties properties;
    @Autowired
    private MeterRegistry meters;

    @Test
    void backgroundRefillTopsUpOnlyPoolsBelowTheWatermark() throws InterruptedException {
        long shop = newShop();
        InventoryKey empty = createInventory(shop, ITEM_A, LOCATION_X, 5000, 100);
        InventoryKey halfFull = createInventory(shop, ITEM_B, LOCATION_X, 5000, 100);
        InventoryKey exhausted = createInventory(shop, ITEM_A, LOCATION_Y, 10, 100);
        refills.refillOneDimension(halfFull, false);
        reserve(halfFull, 50);
        reserve(exhausted, 10);

        new PoolRefillJob(ledgers, refills, properties).scan();

        // 空池被填满到容量；50% 的池高于 25% 水位不动；F=0 的维度没有可发放额度。
        assertQuantities(empty, 5000, 100, 100, 0);
        assertQuantities(halfFull, 5000, 100, 50, 50);
        assertQuantities(exhausted, 10, 10, 0, 10);
    }

    @Test
    void connectionAcquireHoldAndSqlTimeAreAttributedToTheBusinessOperation() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        long reserveHoldsBefore = count("stockhold.connection.hold", "reserve");
        long refillHoldsBefore = count("stockhold.connection.hold", "refill");
        long claimTransactionsBefore = count("stockhold.transaction", "claim");

        // 空池上的首次预留：reserve 事务回滚 -> 同步补充事务 -> reserve 事务提交。
        Reservation reservation = reserve(key, 1);
        reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-metrics");

        assertThat(count("stockhold.connection.hold", "reserve")).isGreaterThan(reserveHoldsBefore);
        // 同步补充由 reserve 触发，但连接归因属于 refill，标签没有串线。
        assertThat(count("stockhold.connection.hold", "refill")).isEqualTo(refillHoldsBefore + 1);
        assertThat(count("stockhold.transaction", "claim")).isEqualTo(claimTransactionsBefore + 1);
        assertThat(count("stockhold.connection.acquire", "claim")).isPositive();
        assertThat(count("stockhold.sql", "reserve")).isPositive();
        assertThat(meters.find("hikaricp.connections.active").gauge()).isNotNull();
    }

    private long count(String name, String operation) {
        return meters.find(name).tag("op", operation).timers().stream().mapToLong(Timer::count).sum();
    }
}
