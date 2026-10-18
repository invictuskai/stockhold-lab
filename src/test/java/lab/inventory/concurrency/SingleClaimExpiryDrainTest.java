package lab.inventory.concurrency;

import org.springframework.test.context.TestPropertySource;

/** 逐个领取（每个事务一个到期预留）的路径同样必须通过全部到期清理测试。 */
@TestPropertySource(properties = "stockhold.expiry.claim-batch-size=1")
class SingleClaimExpiryDrainTest extends ExpiryDrainTest {
}
