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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 文章：部分读流量移到只读副本。只有显式声明 consistency=eventual 的公开查询会读副本；
 * 未配置副本时回落到主库。真实副本由 STOCKHOLD_TEST_REPLICA_JDBC_URL 指定（compose 的 replica profile）。
 */
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
    void eventualReadsAreServedByTheReplicaOnceItHasCaughtUp() throws InterruptedException {
        Assumptions.assumeTrue(replicaReads.configured(),
                "Set STOCKHOLD_TEST_REPLICA_JDBC_URL (compose replica profile + scripts/replica-setup.sh)");
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 3);

        // 副本可能落后：轮询直到它追上，这正是调用方选择 eventual 时接受的语义。
        ReplicaReads.Read<Reservation> read = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                read = replicaReads.reservation(key.shopId(), reservation.header().reservationId(),
                        Consistency.EVENTUAL);
                break;
            } catch (ApiException notYetReplicated) {
                Thread.sleep(50);
            }
        }
        assertThat(read).as("replica caught up within 10s").isNotNull();
        assertThat(read.source()).isEqualTo("replica");
        assertThat(read.value().header().reservationId()).isEqualTo(reservation.header().reservationId());

        ReplicaReads.Read<InventorySnapshot> snapshot = replicaReads.snapshot(key, Consistency.EVENTUAL);
        assertThat(snapshot.source()).isEqualTo("replica");
        assertThat(snapshot.value().activeReserved()).isEqualTo(3);
        assertThat(replicaReads.reservationByKey(key.shopId(), reservation.header().idempotencyKey(),
                Consistency.EVENTUAL).source()).isEqualTo("replica");
    }
}
