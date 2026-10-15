package lab.inventory.concurrency;

import org.springframework.test.context.TestPropertySource;

/** UNION ALL 路径的每个分支必须各自加锁，锁形态与逐维度 SELECT 相同。 */
@TestPropertySource(properties = "stockhold.reserve.union-all-select=true")
class UnionAllReserveLockShapeTest extends ReserveLockShapeTest {
}
