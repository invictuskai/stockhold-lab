package lab.inventory.integration;

import org.springframework.test.context.TestPropertySource;

/** 跨结账批量预留（stockhold.reserve.batching）：批量路径下的完整生命周期与账本数量。 */
@TestPropertySource(properties = {"stockhold.reserve.batching.enabled=true",
        "stockhold.reserve.batching.max-wait-micros=2000"})
class BatchedLifecycleIntegrationTest extends LifecycleIntegrationTest {
}
