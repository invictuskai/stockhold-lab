package lab.inventory.concurrency;

import org.springframework.test.context.TestPropertySource;

/** UNION ALL 路径必须通过与逐维度 SELECT 完全相同的争抢正确性测试，之后才谈性能比较。 */
@TestPropertySource(properties = "stockhold.reserve.union-all-select=true")
class UnionAllReserveContentionTest extends ReserveContentionTest {
}
