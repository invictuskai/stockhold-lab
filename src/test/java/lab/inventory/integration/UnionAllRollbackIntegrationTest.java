package lab.inventory.integration;

import org.springframework.test.context.TestPropertySource;

/** UNION ALL 路径下的整事务回滚、多商品原子性与缺行分组。 */
@TestPropertySource(properties = "stockhold.reserve.union-all-select=true")
class UnionAllRollbackIntegrationTest extends RollbackIntegrationTest {
}
