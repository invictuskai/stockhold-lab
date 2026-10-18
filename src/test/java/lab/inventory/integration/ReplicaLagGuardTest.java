package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import lab.inventory.application.ReplicaReads;
import lab.inventory.application.ReplicaReads.Consistency;
import lab.inventory.domain.InventoryKey;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.MySqlTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 副本延迟保护：心跳年龄超过阈值时 eventual 读回落主库。这里把“副本”指向测试库本身并关闭心跳写入，
 * 直接改写心跳时间来构造新鲜 / 落后两种状态，结果是确定的。
 */
class ReplicaLagGuardTest extends IntegrationTestBase {

    @Autowired
    private ReplicaReads replicaReads;

    @DynamicPropertySource
    static void fakeReplica(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.Coordinates db = MySqlTestDatabase.resolve();
        if (db != null) {
            registry.add("stockhold.replica.url", db::url);
            registry.add("stockhold.replica.username", db::username);
            registry.add("stockhold.replica.password", db::password);
            registry.add("stockhold.replica.heartbeat-enabled", () -> "false");
            registry.add("stockhold.replica.max-staleness-millis", () -> "1500");
        }
    }

    private void heartbeatAgedMillis(long millis) throws InterruptedException {
        jdbc.update("INSERT INTO replica_heartbeat (id, beat_at) VALUES (1, UTC_TIMESTAMP(6) - INTERVAL ? MICROSECOND)"
                + " ON DUPLICATE KEY UPDATE beat_at = VALUES(beat_at)", millis * 1000);
        Thread.sleep(250); // 越过新鲜度判断的缓存
    }

    @Test
    void freshReplicaServesEventualReadsAndALaggingOneFallsBackToThePrimary() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);

        heartbeatAgedMillis(100);
        assertThat(replicaReads.stalenessMillis()).isBetween(100L, 1000L);
        assertThat(replicaReads.snapshot(key, Consistency.EVENTUAL).source()).isEqualTo("replica");

        heartbeatAgedMillis(10_000);
        assertThat(replicaReads.snapshot(key, Consistency.EVENTUAL).source()).isEqualTo("primary-fallback");
        assertThat(replicaReads.snapshot(key, Consistency.STRONG).source()).isEqualTo("primary");

        heartbeatAgedMillis(0);
        assertThat(replicaReads.snapshot(key, Consistency.EVENTUAL).source()).isEqualTo("replica");
    }
}
