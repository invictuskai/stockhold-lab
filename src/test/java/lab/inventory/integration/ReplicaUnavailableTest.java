package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import lab.inventory.application.ReplicaReads;
import lab.inventory.application.ReplicaReads.Consistency;
import lab.inventory.domain.InventoryKey;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 副本不可达时应用照常启动，eventual 读回落主库而不是报错。 */
@TestPropertySource(properties = {"stockhold.replica.url=jdbc:mysql://127.0.0.1:1/unreachable",
        "stockhold.replica.username=nobody", "stockhold.replica.password=none"})
// 该上下文的副本心跳任务会持续写共享的心跳行；类结束后关闭上下文，避免干扰 ReplicaLagGuardTest。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReplicaUnavailableTest extends IntegrationTestBase {

    @Autowired
    private ReplicaReads replicaReads;

    @Test
    void unreachableReplicaFallsBackToThePrimary() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        assertThat(replicaReads.configured()).isTrue();
        assertThat(replicaReads.stalenessMillis()).isNull();
        assertThat(replicaReads.snapshot(key, Consistency.EVENTUAL).source()).isEqualTo("primary-fallback");
    }
}
