package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import lab.inventory.application.FaultInjector.Point;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.migration.LegacyRedisReservations;
import lab.inventory.migration.LegacyRedisReservations.ClaimOrder;
import lab.inventory.migration.MigrationService;
import lab.inventory.migration.Outcome.Category;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.RedisTestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 文章迁移的根本原因：旧架构中预留在 Redis、账本在 MySQL，确认无法在两个系统间原子提交，
 * “取决于顺序，导致超卖或少卖”。这里在两步之间注入失败，复现两个方向；同一时刻的失败在 MySQL 单位池中整体回滚。
 */
@ExtendWith(RedisTestServer.class)
@ActiveProfiles("migration")
class LegacySplitBrainTest extends IntegrationTestBase {

    private static final class Crash extends RuntimeException {
    }

    @Autowired
    private LegacyRedisReservations legacy;
    @Autowired
    private MigrationService migration;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestServer.Address address = RedisTestServer.resolve();
        if (address != null) {
            registry.add("spring.data.redis.host", address::host);
            registry.add("spring.data.redis.port", address::port);
        }
    }

    private InventoryKey seedLegacy(long onHand) {
        InventoryKey key = new InventoryKey(newShop(), ITEM_A, LOCATION_X);
        legacy.seed(key, onHand);
        return key;
    }

    private void legacyReserve(InventoryKey key, String idempotencyKey, int quantity) {
        assertThat(legacy.reserve(idempotencyKey, CanonicalReservationRequest.of(key.shopId(), null,
                List.of(new RawLine(key.inventoryItemId(), key.locationId(), (long) quantity)))).category())
                .isEqualTo(Category.CREATED);
    }

    private void crashBetweenSystems() {
        Thread self = Thread.currentThread();
        faults.install(point -> {
            if (point == Point.LEGACY_CLAIM_BETWEEN_SYSTEMS && Thread.currentThread() == self) {
                throw new Crash();
            }
        });
    }

    @Test
    void healthyLegacyLifecycleKeepsAvailablePlusReservedEqualToLedger() {
        for (ClaimOrder order : ClaimOrder.values()) {
            InventoryKey key = seedLegacy(10);
            legacyReserve(key, "a", 3);
            legacyReserve(key, "b", 2);
            assertThat(legacy.claim(key.shopId(), "a", "pay-a", order).category()).isEqualTo(Category.OK);
            // 重复确认不得再次扣减账本。
            assertThat(legacy.claim(key.shopId(), "a", "pay-a", order).category()).isEqualTo(Category.OK);
            assertThat(legacy.cancel(key.shopId(), "b").category()).isEqualTo(Category.OK);

            LegacyRedisReservations.LegacyDrift drift = legacy.drift(key);
            assertThat(drift.ledgerOnHand()).as("%s", order).isEqualTo(7);
            assertThat(drift.redisAvailable()).isEqualTo(7);
            assertThat(drift.redisReserved()).isZero();
            assertThat(drift.drift()).isZero();
        }
    }

    /** Redis 先确认、账本扣减前失败：已售出但账本未扣减。按账本对账后多出可售量，导致超卖。 */
    @Test
    void redisFirstFailureIsSoldButNotDeductedAndOversellsAfterResync() {
        InventoryKey key = seedLegacy(10);
        legacyReserve(key, "paid", 3);
        crashBetweenSystems();
        assertThatThrownBy(() -> legacy.claim(key.shopId(), "paid", "pay-1", ClaimOrder.REDIS_FIRST))
                .isInstanceOf(Crash.class);
        faults.clear();

        // 3 件已经卖出（Redis 中 CLAIMED），账本仍是 10：账面虚高 3 件。
        assertThat(legacy.drift(key).drift()).isEqualTo(-3);
        legacy.resyncFromLedger(key);
        assertThat(legacy.availableCount(key)).isEqualTo(10);
        // 实物只剩 7 件，旧系统却能再卖 10 件：超卖 3 件。
        legacyReserve(key, "oversold", 10);
    }

    /** 账本先扣减、Redis 确认前失败：已扣减但仍标记为预留。同一件被算了两次，按账本对账后少卖。 */
    @Test
    void ledgerFirstFailureIsDeductedButStillReservedAndUndersells() {
        InventoryKey key = seedLegacy(10);
        legacyReserve(key, "paid", 3);
        crashBetweenSystems();
        assertThatThrownBy(() -> legacy.claim(key.shopId(), "paid", "pay-1", ClaimOrder.LEDGER_FIRST))
                .isInstanceOf(Crash.class);
        faults.clear();

        LegacyRedisReservations.LegacyDrift drift = legacy.drift(key);
        assertThat(drift.ledgerOnHand()).isEqualTo(7);
        assertThat(drift.redisReserved()).isEqualTo(3);
        assertThat(drift.drift()).isEqualTo(3);
        legacy.resyncFromLedger(key);
        // 实物还剩 7 件可卖，旧系统只肯卖 4 件：少卖 3 件。
        assertThat(legacy.availableCount(key)).isEqualTo(4);
        assertThat(legacy.reserve("too-many", CanonicalReservationRequest.of(key.shopId(), null,
                List.of(new RawLine(key.inventoryItemId(), key.locationId(), 5L)))).category())
                .isEqualTo(Category.INSUFFICIENT);
    }

    /** 同一时刻的失败在本系统中不留下任何分歧：账本扣减与预留状态在一个 MySQL 事务里，一起回滚。 */
    @Test
    void theSameFailureInTheUnitPoolRollsBackAsOneTransaction() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 3);
        Thread self = Thread.currentThread();
        faults.install(point -> {
            if (point == Point.CLAIM_AFTER_LEDGER_UPDATE && Thread.currentThread() == self) {
                throw new Crash();
            }
        });
        assertThatThrownBy(() -> reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-1"))
                .isInstanceOf(Crash.class);
        faults.clear();

        assertQuantities(key, 10, 10, 7, 3);
        reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-1");
        assertQuantities(key, 7, 7, 7, 0);
    }

    @Test
    void migrationRoutingKeepsTheLegacyLedgerInStepWhenHealthy() {
        long shopId = newShop();
        InventoryKey key = createInventory(shopId, ITEM_A, LOCATION_X, 20, 1000);
        refills.refillOneDimension(key, false);
        migration.seedLegacy(key, 20);
        migration.setMode(shopId, lab.inventory.migration.MigrationMode.REDIS_PRIMARY);
        migration.reserve(shopId, "m1", null, List.of(line(key, 4)));
        migration.claim(shopId, "m1", "pay-m1");
        migration.setMode(shopId, lab.inventory.migration.MigrationMode.MYSQL_PRIMARY);
        migration.reserve(shopId, "m2", null, List.of(line(key, 2)));
        migration.claim(shopId, "m2", "pay-m2");

        assertThat(migration.legacyDrift(List.of(key))).singleElement()
                .satisfies(d -> {
                    assertThat(d.drift()).isZero();
                    assertThat(d.ledgerOnHand()).isEqualTo(14);
                });
        assertQuantities(key, 14, 14, 14, 0);
    }
}
