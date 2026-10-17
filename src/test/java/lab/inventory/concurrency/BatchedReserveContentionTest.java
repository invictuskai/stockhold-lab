package lab.inventory.concurrency;

import org.springframework.test.context.TestPropertySource;

/** 跨结账批量预留（stockhold.reserve.batching）：批量路径必须通过与单独预留完全相同的争抢正确性测试。 */
@TestPropertySource(properties = {"stockhold.reserve.batching.enabled=true",
        "stockhold.reserve.batching.max-wait-micros=2000"})
class BatchedReserveContentionTest extends ReserveContentionTest {
}
