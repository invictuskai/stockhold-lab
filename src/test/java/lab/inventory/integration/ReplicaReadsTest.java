package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import lab.inventory.application.ReplicaReads;
import lab.inventory.application.ReplicaReads.Consistency;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.support.IntegrationTestBase;
import lab.inventory.support.MySqlTestDatabase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 文章：部分读流量移到只读副本。只有显式声明 consistency=eventual 的公开查询会读副本；
 * 未配置副本时回落到主库。真实副本由 STOCKHOLD_TEST_REPLICA_JDBC_URL 指定（compose 的 replica profile）。
 */
// 该上下文的副本心跳任务会持续写共享的心跳行；类结束后关闭上下文，避免干扰 ReplicaLagGuardTest。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReplicaReadsTest extends IntegrationTestBase {

    private static final String REPLICA_URL = System.getenv("STOCKHOLD_TEST_REPLICA_JDBC_URL");

    @Autowired
    private ReplicaReads replicaReads;

    @DynamicPropertySource
    static void replica(DynamicPropertyRegistry registry) {
        if (REPLICA_URL != null && !REPLICA_URL.isBlank()) {
            MySqlTestDatabase.Coordinates db = MySqlTestDatabase.resolve();
            registry.add("stockhold.replica.url",
                    () -> REPLICA_URL.contains("?") ? REPLICA_URL : REPLICA_URL + "?" + MySqlTestDatabase.URL_PARAMS);
            registry.add("stockhold.replica.username", db::username);
            registry.add("stockhold.replica.password", db::password);
        }
    }

    @Test
    void strongReadsAlwaysUseThePrimary() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 2);

        assertThat(replicaReads.snapshot(key, Consistency.STRONG).source()).isEqualTo("primary");
        assertThat(replicaReads.reservation(key.shopId(), reservation.header().reservationId(), Consistency.STRONG)
                .value().header().status().name()).isEqualTo("ACTIVE");
    }

    @Test
    void consistencyParameterIsValidated() {
        assertThat(Consistency.parse(null)).isEqualTo(Consistency.STRONG);
        assertThat(Consistency.parse("EVENTUAL")).isEqualTo(Consistency.EVENTUAL);
        assertThatThrownBy(() -> Consistency.parse("stale")).isInstanceOf(ApiException.class);
    }

    @Test
    void eventualReadsFallBackToThePrimaryWithoutAReplica() {
        Assumptions.assumeTrue(!replicaReads.configured(), "a replica is configured");
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        assertThat(replicaReads.snapshot(key, Consistency.EVENTUAL).source()).isEqualTo("primary");
    }

    @Test
    void eventualReadsNeverReportReplicationLagAsNotFound() throws InterruptedException {
        Assumptions.assumeTrue(replicaReads.configured(),
                "Set STOCKHOLD_TEST_REPLICA_JDBC_URL (compose replica profile + scripts/replica-setup.sh)");
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 3);

        // 刚创建：副本可能还没有，读取回落主库，但绝不返回 404。
        ReplicaReads.Read<Reservation> immediate = replicaReads.reservation(key.shopId(),
                reservation.header().reservationId(), Consistency.EVENTUAL);
        assertThat(immediate.source()).isIn("replica", "primary-fallback");
        assertThat(immediate.value().header().reservationId()).isEqualTo(reservation.header().reservationId());

        // 副本追上后由副本服务。
        String source = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            source = replicaReads.reservation(key.shopId(), reservation.header().reservationId(),
                    Consistency.EVENTUAL).source();
            if (source.equals("replica")) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(source).as("replica served the read within 10s").isEqualTo("replica");
        assertThat(replicaReads.stalenessMillis()).isNotNull().isLessThan(1500L);

        ReplicaReads.Read<InventorySnapshot> snapshot = replicaReads.snapshot(key, Consistency.EVENTUAL);
        assertThat(snapshot.source()).isEqualTo("replica");
        assertThat(snapshot.value().activeReserved()).isEqualTo(3);
        assertThat(replicaReads.reservationByKey(key.shopId(), reservation.header().idempotencyKey(),
                Consistency.EVENTUAL).source()).isEqualTo("replica");
    }
}
