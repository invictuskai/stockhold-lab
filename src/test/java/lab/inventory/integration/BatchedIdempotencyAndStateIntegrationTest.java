package lab.inventory.integration;

import org.springframework.test.context.TestPropertySource;

/** 跨结账批量预留（stockhold.reserve.batching）：批量路径下的幂等、状态机与到期语义与单独预留相同。 */
@TestPropertySource(properties = {"stockhold.reserve.batching.enabled=true",
        "stockhold.reserve.batching.max-wait-micros=2000"})
class BatchedIdempotencyAndStateIntegrationTest extends IdempotencyAndStateIntegrationTest {
}
