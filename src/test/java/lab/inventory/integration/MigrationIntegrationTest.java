package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.migration.LegacyRedisReservations;
import lab.inventory.migration.MigrationMode;
import lab.inventory.migration.MigrationService;
import lab.inventory.migration.Outcome.Category;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.RedisTestServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 文章的迁移策略：Redis 为事实来源、MySQL 影子执行并比对，然后切换为 MySQL 为准、Redis 双写作为回切开关。
 * 断言的重点是在途预留跨越切换仍可确认 / 取消、两个系统最终一致，以及分歧会被记录而不是被掩盖。
 */
@ExtendWith(RedisTestServer.class)
@ActiveProfiles("migration")
class MigrationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MigrationService migration;
    @Autowired
    private LegacyRedisReservations legacy;
    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        RedisTestServer.Address address = RedisTestServer.resolve();
        if (address != null) {
            registry.add("spring.data.redis.host", address::host);
            registry.add("spring.data.redis.port", address::port);
        }
    }

    /** 两个系统用同样的账面库存初始化；MySQL 的池先补满，与 Redis 的可售计数对应同一个 H。 */
    private InventoryKey seedBoth(long shopId, long itemId, long onHand) {
        InventoryKey key = createInventory(shopId, itemId, LOCATION_X, onHand, 1000);
        refills.refillOneDimension(key, false);
        migration.seedLegacy(key, onHand);
        return key;
    }

    private MigrationService.Result reserve(InventoryKey key, String idempotencyKey, int quantity) {
        return migration.reserve(key.shopId(), idempotencyKey, null,
                List.of(new RawLine(key.inventoryItemId(), key.locationId(), (long) quantity)));
    }

    private long mismatchesFor(long shopId) {
        return migration.mismatches().stream().filter(m -> m.shopId() == shopId).count();
    }

    @Test
    void redisPrimaryShadowsEveryOperationIntoMysqlWithoutDivergence() {
        long shopId = newShop();
        InventoryKey key = seedBoth(shopId, ITEM_A, 100);
        migration.setMode(shopId, MigrationMode.REDIS_PRIMARY);

        assertThat(reserve(key, "k1", 3).outcome().category()).isEqualTo(Category.CREATED);
        assertThat(reserve(key, "k2", 2).primarySystem()).isEqualTo("redis");
        assertThat(reserve(key, "k1", 3).outcome().category()).isEqualTo(Category.REPLAYED);
        assertThat(errorCodeOf(() -> reserve(key, "k1", 4))).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        migration.claim(shopId, "k1", "pay-k1");
        migration.cancel(shopId, "k2");
        migration.cancel(shopId, "k2");
        assertThat(errorCodeOf(() -> migration.claim(shopId, "k2", "pay-k2")))
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATE);

        assertThat(mismatchesFor(shopId)).isZero();
        // MySQL 影子：确认 3 件、取消 2 件；Redis 可售 = 100 - 3 = MySQL 的 H - R。
        assertQuantities(key, 97, 95, 95, 0);
        assertThat(legacy.availableCount(key)).isEqualTo(97);
        assertThat(migration.consistency(shopId, List.of(key)).consistent()).isTrue();
    }

    @Test
    void inFlightReservationsSurviveCutoverAndKillSwitch() {
        long shopId = newShop();
        InventoryKey key = seedBoth(shopId, ITEM_A, 50);
        migration.setMode(shopId, MigrationMode.REDIS_PRIMARY);
        reserve(key, "before-a", 1);
        reserve(key, "before-b", 2);

        // 切换：MySQL 成为事实来源；切换前在 Redis 为主时创建的预留，在 MySQL 中由影子写入，因此可以确认 / 取消。
        migration.setMode(shopId, MigrationMode.MYSQL_PRIMARY);
        MigrationService.Result claimed = migration.claim(shopId, "before-a", "pay-a");
        assertThat(claimed.primarySystem()).isEqualTo("mysql");
        assertThat(claimed.mysqlReservationId()).isNotNull();
        reserve(key, "after-c", 4);

        // 回切开关：Redis 一直被双写，仍持有切换后创建的预留。
        migration.setMode(shopId, MigrationMode.REDIS_PRIMARY);
        assertThat(migration.claim(shopId, "after-c", "pay-c").primarySystem()).isEqualTo("redis");
        migration.cancel(shopId, "before-b");

        assertThat(mismatchesFor(shopId)).isZero();
        MigrationService.Consistency consistency = migration.consistency(shopId, List.of(key));
        assertThat(consistency.consistent()).as("%s", consistency).isTrue();
        assertThat(consistency.mysqlStatusCounts()).containsEntry("CLAIMED", 2L).containsEntry("CANCELLED", 1L);
        // 确认 1 + 4 件，取消 2 件：H = 50 - 5，A = H - 已释放的 2。
        assertQuantities(key, 45, 43, 43, 0);
    }

    @Test
    void shadowDivergenceIsRecordedAndReportedNotHidden() {
        long shopId = newShop();
        // 旧系统与 MySQL 的账面不一致（迁移前就存在的漂移）：Redis 认为有 10 件，MySQL 只有 5 件。
        InventoryKey key = seedBoth(shopId, ITEM_A, 5);
        migration.seedLegacy(key, 10);
        migration.setMode(shopId, MigrationMode.REDIS_PRIMARY);

        // 事实来源（Redis）成功，影子（MySQL）库存不足：响应以主系统为准，分歧被记录。
        assertThat(reserve(key, "drift", 8).outcome().category()).isEqualTo(Category.CREATED);

        List<MigrationService.Mismatch> mismatches = migration.mismatches().stream()
                .filter(m -> m.shopId() == shopId).toList();
        assertThat(mismatches).singleElement().satisfies(m -> {
            assertThat(m.primary().category()).isEqualTo(Category.CREATED);
            assertThat(m.shadow().category()).isEqualTo(Category.INSUFFICIENT);
        });
        assertThat(migration.consistency(shopId, List.of(key)).consistent()).isFalse();
        assertQuantities(key, 5, 5, 5, 0);
    }

    @Test
    void shadowIsSkippedWhenThePrimaryRejects() {
        long shopId = newShop();
        InventoryKey key = seedBoth(shopId, ITEM_A, 2);
        migration.setMode(shopId, MigrationMode.REDIS_PRIMARY);

        assertThat(errorCodeOf(() -> reserve(key, "too-many", 3))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        // 影子不能产生主系统拒绝了的状态。
        assertQuantities(key, 2, 2, 2, 0);
        assertThat(mismatchesFor(shopId)).isZero();
    }

    @Test
    void legacyMultiLineReserveIsAtomic() {
        long shopId = newShop();
        InventoryKey a = seedBoth(shopId, ITEM_A, 5);
        InventoryKey b = seedBoth(shopId, ITEM_B, 1);
        migration.setMode(shopId, MigrationMode.REDIS_ONLY);

        assertThat(errorCodeOf(() -> migration.reserve(shopId, "cart", null, List.of(
                new RawLine(a.inventoryItemId(), a.locationId(), 2L),
                new RawLine(b.inventoryItemId(), b.locationId(), 2L))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        assertThat(legacy.availableCount(a)).isEqualTo(5);
        assertThat(legacy.availableCount(b)).isEqualTo(1);
    }

    @Test
    void bothSystemsExpireIndependentlyAndConverge() {
        long shopId = newShop();
        InventoryKey key = seedBoth(shopId, ITEM_A, 10);
        migration.setMode(shopId, MigrationMode.REDIS_PRIMARY);
        reserve(key, "expiring", 4);

        // 把两个系统中的到期时间都改到过去，代替真实等待 TTL；随后各自的清理任务释放额度。
        String hash = "sh:{" + shopId + "}:res:expiring";
        redis.opsForHash().put(hash, "expiresAt", "0");
        redis.opsForZSet().add("sh:{" + shopId + "}:exp", "expiring", 0);
        jdbc.update("UPDATE reservations SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND"
                + " WHERE shop_id = ? AND idempotency_key = ?", shopId, "expiring");
        legacy.sweepExpired();
        var reservation = reservations.getByIdempotencyKey(shopId, "expiring");
        assertThat(expiry.expireOne(shopId, reservation.header().reservationId())).isTrue();

        assertThat(errorCodeOf(() -> migration.claim(shopId, "expiring", "pay-late")))
                .isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        assertThat(legacy.availableCount(key)).isEqualTo(10);
        assertQuantities(key, 10, 6, 6, 0);
        assertThat(migration.consistency(shopId, List.of(key)).consistent()).isTrue();
        assertThat(mismatchesFor(shopId)).isZero();
    }
}
