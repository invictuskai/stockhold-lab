package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import lab.inventory.application.TransactionRunner;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 实现与验证计划 4.4 / 4.5：状态竞争、死锁与锁等待超时。 */
class StateRaceTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private TransactionRunner transactions;
    @Autowired
    private InventoryLedgerRepository ledgers;

    @Test
    void onePaymentReferenceClaimsAtMostOneReservation() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 1000);
        long shop = key.shopId();
        int rounds = 15;
        for (int round = 0; round < rounds; round++) {
            Reservation first = reserve(key, 2);
            Reservation second = reserve(key, 3);
            String reference = "pay-race-" + round;

            List<Outcome<Reservation>> outcomes = runConcurrently(List.of(
                    () -> reservations.claim(shop, first.header().reservationId(), reference),
                    () -> reservations.claim(shop, second.header().reservationId(), reference)));

            assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::code))
                    .containsExactly(ErrorCode.PAYMENT_REFERENCE_CONFLICT);
            // 失败一方的扣减全部回滚，随后取消它以便下一轮断言。
            Reservation loser = outcomes.get(0).succeeded() ? second : first;
            assertThat(reservations.get(shop, loser.header().reservationId()).header().status())
                    .isEqualTo(ReservationStatus.ACTIVE);
            reservations.cancel(shop, loser.header().reservationId());
            assertInvariants(key);
        }
        assertThat(inventory.snapshot(key).activeReserved()).isZero();
        Long claimed = jdbc.queryForObject("SELECT COALESCE(SUM(total_quantity), 0) FROM reservations"
                + " WHERE shop_id = ? AND status = 'CLAIMED'", Long.class, shop);
        assertThat(inventory.snapshot(key).onHand()).isEqualTo(1000 - claimed);
    }

    @Test
    void claimAndCancelRaceToExactlyOneTerminalState() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 1000);
        long shop = key.shopId();
        long expectedOnHand = 1000;
        for (int round = 0; round < 20; round++) {
            Reservation reservation = reserve(key, 3);
            String reference = "pay-cc-" + round;

            List<Outcome<Reservation>> outcomes = runConcurrently(List.of(
                    () -> reservations.claim(shop, reservation.header().reservationId(), reference),
                    () -> reservations.cancel(shop, reservation.header().reservationId())));

            assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
            assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::code))
                    .containsExactly(ErrorCode.INVALID_RESERVATION_STATE);
            ReservationStatus status = reservations.get(shop, reservation.header().reservationId())
                    .header().status();
            if (outcomes.get(0).succeeded()) {
                assertThat(status).isEqualTo(ReservationStatus.CLAIMED);
                expectedOnHand -= 3;
            } else {
                assertThat(status).isEqualTo(ReservationStatus.CANCELLED);
            }
            // 扣减或释放恰好发生一次。
            assertThat(inventory.snapshot(key).onHand()).isEqualTo(expectedOnHand);
            assertThat(inventory.snapshot(key).activeReserved()).isZero();
            assertInvariants(key);
        }
    }

    @Test
    void claimNeverSucceedsOnceExpiryIsDueAndReleaseHappensOnce() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        long shop = key.shopId();
        for (int round = 0; round < 10; round++) {
            Reservation reservation = reserve(key, 4);
            forceExpired(reservation);
            String reference = "pay-late-" + round;
            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> reservations.claim(shop, reservation.header().reservationId(), reference));
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> expiry.expireOne(shop, reservation.header().reservationId()));
            }

            List<Outcome<Object>> outcomes = runConcurrently(tasks);

            // 以头锁保护下的时间检查为准：不允许扣减与释放同时发生。
            assertThat(outcomes.get(0).code()).isEqualTo(ErrorCode.RESERVATION_EXPIRED);
            // 被头锁挡住而超时的实例返回 false，留给下一轮；补一轮后必定已释放且只释放一次。
            expiry.expireOne(shop, reservation.header().reservationId());
            assertThat(outcomes.subList(1, 5).stream().filter(o -> Boolean.TRUE.equals(o.value())).count())
                    .isLessThanOrEqualTo(1);
            assertThat(reservations.get(shop, reservation.header().reservationId()).header().status())
                    .isEqualTo(ReservationStatus.EXPIRED);
            assertThat(inventory.snapshot(key).onHand()).isEqualTo(100);
            assertThat(inventory.snapshot(key).activeReserved()).isZero();
            assertInvariants(key);
        }
    }

    @Test
    void manyExpiryInstancesReleaseEachReservationOnce() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 500, 1000);
        long shop = key.shopId();
        List<Reservation> due = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Reservation reservation = reserve(key, 2);
            forceExpired(reservation);
            due.add(reservation);
        }
        AtomicInteger released = new AtomicInteger();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int instance = 0; instance < 8; instance++) {
            tasks.add(() -> {
                int mine = 0;
                for (Reservation reservation : due) {
                    if (expiry.expireOne(shop, reservation.header().reservationId())) {
                        mine++;
                    }
                }
                released.addAndGet(mine);
                return mine;
            });
        }

        assertThat(runConcurrently(tasks)).allMatch(Outcome::succeeded);
        // 收尾一轮，处理并发时因锁等待被跳过的候选。
        for (Reservation reservation : due) {
            if (expiry.expireOne(shop, reservation.header().reservationId())) {
                released.incrementAndGet();
            }
        }

        assertThat(released.get()).isEqualTo(30);
        assertQuantities(key, 500, 440, 440, 0);
    }

    @Test
    void lockWaitTimeoutNeverCommitsAPartialClaim() throws Exception {
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 10, 1000);
        Reservation reservation = reservations.reserve(a.shopId(), newKey("timeout"), null,
                List.of(line(a, 3), line(b, 2))).reservation();

        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            // 另一连接持有第二个维度的账本行锁：claim 更新完 A 后在 B 上等待并超时（1205）。
            try (PreparedStatement lock = blocker.prepareStatement("SELECT on_hand_quantity FROM inventory_ledger"
                    + " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ? FOR UPDATE")) {
                lock.setLong(1, b.shopId());
                lock.setLong(2, b.inventoryItemId());
                lock.setLong(3, b.locationId());
                lock.executeQuery().close();
            }

            assertThat(errorCodeOf(() -> reservations.claim(a.shopId(), reservation.header().reservationId(),
                    "pay-timeout"))).isEqualTo(ErrorCode.INVENTORY_BUSY);
            blocker.rollback();
        }

        assertThat(reservations.get(a.shopId(), reservation.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(a, 10, 10, 7, 3);
        assertQuantities(b, 10, 10, 8, 2);

        reservations.claim(a.shopId(), reservation.header().reservationId(), "pay-timeout");
        assertQuantities(a, 7, 7, 7, 0);
        assertQuantities(b, 8, 8, 8, 0);
    }

    @Test
    void deadlockVictimIsRolledBackAndRetriedAsAWholeTransaction() throws InterruptedException {
        // 人工构造反向锁顺序：两个事务各持一个账本锁再请求对方的。
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 100, 1000);
        CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();

        List<Outcome<Boolean>> outcomes = runConcurrently(List.of(
                () -> crossLock(a, b, bothHoldFirstLock, attempts),
                () -> crossLock(b, a, bothHoldFirstLock, attempts)));

        assertThat(outcomes).allMatch(o -> o.succeeded() && o.value());
        // 两个事务最终都完整提交，其中至少一个经历过整事务重试。
        assertThat(attempts.get()).isGreaterThanOrEqualTo(3);
        // 每个提交的事务给两个维度各 +1；被回滚的尝试不留任何部分更新。
        assertThat(inventory.snapshot(a).onHand()).isEqualTo(102);
        assertThat(inventory.snapshot(b).onHand()).isEqualTo(102);
    }

    private boolean crossLock(InventoryKey first, InventoryKey second, CyclicBarrier barrier,
            AtomicInteger attempts) {
        return transactions.executeWithRetry(BusinessOperation.ADMIN, () -> {
            boolean firstAttempt = attempts.getAndIncrement() < 2;
            long firstOnHand = ledgers.lockForUpdate(first).orElseThrow().onHand();
            if (firstAttempt) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            // 先写第一个维度再请求第二个锁：死锁牺牲者必须连同这次写入一起回滚。
            ledgers.updateOnHand(first, firstOnHand + 1);
            long secondOnHand = ledgers.lockForUpdate(second).orElseThrow().onHand();
            return ledgers.updateOnHand(second, secondOnHand + 1) == 1;
        });
    }
}
