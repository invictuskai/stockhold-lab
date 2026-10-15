package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** 实现与验证计划 4.4 中可以确定性顺序构造的幂等与状态机场景。 */
class IdempotencyAndStateIntegrationTest extends IntegrationTestBase {

    @Test
    void sameKeySameRequestReturnsTheSameReservationWithoutTakingMoreStock() {
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 100, 1000);
        String key = newKey("checkout");

        ReserveOutcome first = reservations.reserve(a.shopId(), key, 300, List.of(line(a, 3), line(b, 2)));
        // 行顺序变化、拆分的重复行、省略默认 TTL：规范化后是同一个请求。
        ReserveOutcome replay = reservations.reserve(a.shopId(), key, null,
                List.of(line(b, 2), line(a, 1), line(a, 2)));

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.reservation().header().reservationId())
                .isEqualTo(first.reservation().header().reservationId());
        assertThat(replay.reservation().lines()).extracting(Reservation.Line::lineNo, Reservation.Line::quantity)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, 3), org.assertj.core.groups.Tuple.tuple(2, 2));
        assertQuantities(a, 100, 100, 97, 3);
        assertQuantities(b, 100, 100, 98, 2);
    }

    @Test
    void sameKeyDifferentRequestConflicts() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        String idempotencyKey = newKey("checkout");
        reservations.reserve(key.shopId(), idempotencyKey, null, List.of(line(key, 3)));

        assertThat(errorCodeOf(() -> reservations.reserve(key.shopId(), idempotencyKey, null,
                List.of(line(key, 4))))).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(errorCodeOf(() -> reservations.reserve(key.shopId(), idempotencyKey, 60,
                List.of(line(key, 3))))).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertQuantities(key, 100, 100, 97, 3);
    }

    @Test
    void replayReturnsCurrentTerminalStateAndExpiredActiveDoesNotCreateNewHold() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100, 1000);
        String idempotencyKey = newKey("checkout");
        Reservation created = reservations.reserve(key.shopId(), idempotencyKey, null, List.of(line(key, 3)))
                .reservation();

        // 超时但尚未清理的 ACTIVE：重复 reserve 返回原预留，不创建新的库存占用。
        forceExpired(created);
        ReserveOutcome replayWhileStale = reservations.reserve(key.shopId(), idempotencyKey, null,
                List.of(line(key, 3)));
        assertThat(replayWhileStale.replayed()).isTrue();
        assertThat(replayWhileStale.reservation().header().status()).isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(key, 100, 100, 97, 3);

        expiry.expireOne(key.shopId(), created.header().reservationId());
        ReserveOutcome replayAfterExpiry = reservations.reserve(key.shopId(), idempotencyKey, null,
                List.of(line(key, 3)));
        assertThat(replayAfterExpiry.reservation().header().status()).isEqualTo(ReservationStatus.EXPIRED);
        assertQuantities(key, 100, 97, 97, 0);
    }

    @Test
    void idempotencyKeysAndInventoryAreIsolatedPerShop() {
        InventoryKey shopOne = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        InventoryKey shopTwo = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        String sharedKey = newKey("shared");

        Reservation one = reservations.reserve(shopOne.shopId(), sharedKey, null, List.of(line(shopOne, 2)))
                .reservation();
        ReserveOutcome two = reservations.reserve(shopTwo.shopId(), sharedKey, null, List.of(line(shopTwo, 5)));

        assertThat(two.replayed()).isFalse();
        assertThat(two.reservation().header().reservationId()).isNotEqualTo(one.header().reservationId());
        assertQuantities(shopOne, 10, 10, 8, 2);
        assertQuantities(shopTwo, 10, 10, 5, 5);
        // 跨 shop 访问他人的预留一律按不存在处理。
        assertThat(errorCodeOf(() -> reservations.get(shopTwo.shopId(), one.header().reservationId())))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        assertThat(errorCodeOf(() -> reservations.claim(shopTwo.shopId(), one.header().reservationId(), "pay")))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        assertThat(errorCodeOf(() -> reservations.cancel(shopTwo.shopId(), one.header().reservationId())))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    void repeatedClaimDeductsOnceEvenAfterTtlHasPassed() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 3);
        reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-1");

        // 已 CLAIMED 在 TTL 之后重试 claim：幂等成功。
        forceExpired(reservation);
        Reservation replay = reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-1");
        assertThat(replay.header().status()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(replay.header().paymentReference()).isEqualTo("pay-1");
        assertQuantities(key, 7, 7, 7, 0);

        assertThat(errorCodeOf(() -> reservations.claim(key.shopId(), reservation.header().reservationId(),
                "pay-2"))).isEqualTo(ErrorCode.PAYMENT_REFERENCE_CONFLICT);
        assertThat(expiry.expireOne(key.shopId(), reservation.header().reservationId())).isFalse();
        assertQuantities(key, 7, 7, 7, 0);
    }

    @Test
    void paymentReferenceCannotClaimTwoReservationsAndFailedClaimRollsBackLedger() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation first = reserve(key, 3);
        Reservation second = reserve(key, 2);
        reservations.claim(key.shopId(), first.header().reservationId(), "pay-shared");
        assertQuantities(key, 7, 7, 5, 2);

        assertThat(errorCodeOf(() -> reservations.claim(key.shopId(), second.header().reservationId(),
                "pay-shared"))).isEqualTo(ErrorCode.PAYMENT_REFERENCE_CONFLICT);

        // 第二次 claim 的账本扣减已随事务回滚，预留仍为 ACTIVE。
        assertThat(reservations.get(key.shopId(), second.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(key, 7, 7, 5, 2);
    }

    @Test
    void terminalStatesNeverRevive() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 30, 1000);
        Reservation claimed = reserve(key, 1);
        Reservation cancelled = reserve(key, 1);
        Reservation expired = reserve(key, 1);
        long shop = key.shopId();
        reservations.claim(shop, claimed.header().reservationId(), "pay-a");
        reservations.cancel(shop, cancelled.header().reservationId());
        forceExpired(expired);
        expiry.expireOne(shop, expired.header().reservationId());

        assertThat(errorCodeOf(() -> reservations.cancel(shop, claimed.header().reservationId())))
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATE);
        assertThat(reservations.cancel(shop, cancelled.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(errorCodeOf(() -> reservations.claim(shop, cancelled.header().reservationId(), "pay-b")))
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATE);
        assertThat(errorCodeOf(() -> reservations.cancel(shop, expired.header().reservationId())))
                .isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        assertThat(errorCodeOf(() -> reservations.claim(shop, expired.header().reservationId(), "pay-c")))
                .isEqualTo(ErrorCode.RESERVATION_EXPIRED);

        assertQuantities(key, 29, 27, 27, 0);
    }

    @Test
    void invalidTokensAreRejectedBeforeAnyDatabaseWork() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 1);

        assertThat(errorCodeOf(() -> reservations.reserve(key.shopId(), "has space", null, List.of(line(key, 1)))))
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> reservations.reserve(key.shopId(), null, null, List.of(line(key, 1)))))
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> reservations.reserve(key.shopId(), "k".repeat(129), null,
                List.of(line(key, 1))))).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> reservations.claim(key.shopId(), reservation.header().reservationId(),
                "pay'; DROP TABLE reservations; --"))).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertQuantities(key, 10, 10, 9, 1);
    }

    @Test
    void sweepReleasesOnlyDueReservations() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 30, 1000);
        Reservation due = reserve(key, 4);
        Reservation alsoDue = reserve(key, 5);
        Reservation notDue = reserve(key, 6);
        forceExpired(due);
        forceExpired(alsoDue);

        // 扫描是全局的，循环到没有可释放的候选为止。
        while (expiry.sweep(200) > 0) {
            // 继续下一页
        }

        long shop = key.shopId();
        assertThat(reservations.get(shop, due.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.get(shop, alsoDue.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservations.get(shop, notDue.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(key, 30, 21, 15, 6);
    }
}
