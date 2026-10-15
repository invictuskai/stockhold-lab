package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import lab.inventory.application.InventoryService.OperationOutcome;
import lab.inventory.application.PoolRefillService.RefillResult;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** 实现与验证计划 4.1：基础生命周期中 H / A / P / R 的每一步变化。 */
class LifecycleIntegrationTest extends IntegrationTestBase {

    @Test
    void initializeRefillReserveClaim() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        assertQuantities(key, 10, 0, 0, 0);

        RefillResult refill = refills.refillOneDimension(key, false);
        assertThat(refill.insertedUnits()).isEqualTo(10);
        assertQuantities(key, 10, 10, 10, 0);

        Reservation reservation = reserve(key, 3);
        assertThat(reservation.header().status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(reservation.header().expiresAt())
                .isEqualTo(reservation.header().createdAt().plusSeconds(300));
        assertQuantities(key, 10, 10, 7, 3);

        Reservation claimed = reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-1");
        assertThat(claimed.header().status()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(claimed.header().claimedAt()).isNotNull();
        assertQuantities(key, 7, 7, 7, 0);
    }

    @Test
    void cancelReturnsAllocationToLedgerAndNextRefillReusesIt() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        refills.refillOneDimension(key, false);
        Reservation reservation = reserve(key, 3);

        Reservation cancelled = reservations.cancel(key.shopId(), reservation.header().reservationId());
        assertThat(cancelled.header().status()).isEqualTo(ReservationStatus.CANCELLED);
        assertQuantities(key, 10, 7, 7, 0);
        assertThat(inventory.snapshot(key).unallocated()).isEqualTo(3);

        // 回收的 F 再次进入池，不增加 H。
        assertThat(refills.refillOneDimension(key, false).insertedUnits()).isEqualTo(3);
        assertQuantities(key, 10, 10, 10, 0);
    }

    @Test
    void firstReserveRefillsEmptyPoolSynchronously() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 5000, 100);

        reserve(key, 3);

        assertQuantities(key, 5000, 100, 97, 3);
    }

    @Test
    void walkthroughFromDesignDocument() {
        // 总体技术方案 4.4 的实例。
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 100_000, 1000);
        refills.refillOneDimension(key, false);
        assertQuantities(key, 100_000, 1000, 1000, 0);

        Reservation reservation = reserve(key, 800);
        assertQuantities(key, 100_000, 1000, 200, 800);

        refills.refillOneDimension(key, false);
        assertQuantities(key, 100_000, 1800, 1000, 800);

        reservations.claim(key.shopId(), reservation.header().reservationId(), "pay-800");
        assertQuantities(key, 99_200, 1000, 1000, 0);
        InventorySnapshot snapshot = inventory.snapshot(key);
        assertThat(snapshot.effectiveAvailable()).isEqualTo(99_200);
    }

    @Test
    void zeroStockIsReportedAsInsufficientWithoutResidue() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 0, 1000);
        String idempotencyKey = newKey("empty");

        assertThat(errorCodeOf(() -> reservations.reserve(key.shopId(), idempotencyKey, null,
                List.of(line(key, 1))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        assertQuantities(key, 0, 0, 0, 0);
        // 失败请求不占用幂等键。
        assertThat(errorCodeOf(() -> reservations.getByIdempotencyKey(key.shopId(), idempotencyKey)))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    void fullyReservedStockIsInsufficientRatherThanBusy() {
        // P=0 且 F=0：全部额度都是 ACTIVE 预留，必须报告真实不足而不是无限补充。
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 5, 1000);
        reserve(key, 5);
        assertQuantities(key, 5, 5, 0, 5);

        assertThat(errorCodeOf(() -> reserve(key, 1))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertQuantities(key, 5, 5, 0, 5);
    }

    @Test
    void expiredButUncleanedReservationCannotBeClaimedAndStillHoldsAllocation() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 4);
        forceExpired(reservation);

        assertThat(errorCodeOf(() -> reservations.claim(key.shopId(), reservation.header().reservationId(),
                "pay-late"))).isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        // 查询仍是 ACTIVE，额度仍占用，直到 expiry 提交。
        assertThat(reservations.get(key.shopId(), reservation.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(key, 10, 10, 6, 4);

        assertThat(expiry.expireOne(key.shopId(), reservation.header().reservationId())).isTrue();
        Reservation expired = reservations.get(key.shopId(), reservation.header().reservationId());
        assertThat(expired.header().status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(expired.header().expiredAt()).isNotNull();
        assertQuantities(key, 10, 6, 6, 0);

        // 重复到期处理不会再次释放。
        assertThat(expiry.expireOne(key.shopId(), reservation.header().reservationId())).isFalse();
        assertQuantities(key, 10, 6, 6, 0);
    }

    @Test
    void expiryDoesNotReleaseReservationsThatAreNotDue() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 2);

        assertThat(expiry.expireOne(key.shopId(), reservation.header().reservationId())).isFalse();
        assertQuantities(key, 10, 10, 8, 2);
    }

    @Test
    void expiredActiveReservationCanStillBeCancelled() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation reservation = reserve(key, 2);
        forceExpired(reservation);

        assertThat(reservations.cancel(key.shopId(), reservation.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertQuantities(key, 10, 8, 8, 0);
    }

    @Test
    void createIsIdempotentPerOperationId() {
        long shop = newShop();
        InventoryKey key = new InventoryKey(shop, ITEM_A, LOCATION_X);
        UUID operationId = UUID.randomUUID();

        OperationOutcome first = inventory.create(key, operationId, 100, 50);
        assertThat(first.replayed()).isFalse();
        reserve(key, 10);

        // 幂等响应是当时的操作快照，不是当前库存。
        OperationOutcome replay = inventory.create(key, operationId, 100, 50);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operation().onHandAfter()).isEqualTo(100);
        assertThat(replay.operation().allocatedAfter()).isZero();

        assertThat(errorCodeOf(() -> inventory.create(key, operationId, 101, 50)))
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(errorCodeOf(() -> inventory.create(key, UUID.randomUUID(), 100, 50)))
                .isEqualTo(ErrorCode.INVENTORY_ALREADY_EXISTS);
        assertThat(errorCodeOf(() -> inventory.create(new InventoryKey(shop, ITEM_B, LOCATION_X),
                UUID.randomUUID(), 1, 1001))).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertQuantities(key, 100, 50, 40, 10);
    }

    @Test
    void adjustmentsAreIdempotentAndNeverDropBelowAllocated() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        refills.refillOneDimension(key, false);
        UUID restock = UUID.randomUUID();

        assertThat(inventory.adjust(key, restock, 500).operation().onHandAfter()).isEqualTo(510);
        OperationOutcome replay = inventory.adjust(key, restock, 500);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.operation().onHandAfter()).isEqualTo(510);
        assertQuantities(key, 510, 10, 10, 0);

        assertThat(errorCodeOf(() -> inventory.adjust(key, restock, 499)))
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);

        // 新 H 必须 >= A：即使额度只是放在池中也不允许减掉。
        UUID tooDeep = UUID.randomUUID();
        assertThat(errorCodeOf(() -> inventory.adjust(key, tooDeep, -501))).isEqualTo(ErrorCode.STOCK_ALLOCATED);
        // 失败的操作不留记录，同一个 operationId 之后仍可用于合法请求。
        assertThat(inventory.adjust(key, tooDeep, -500).operation().onHandAfter()).isEqualTo(10);
        assertQuantities(key, 10, 10, 10, 0);

        assertThat(errorCodeOf(() -> inventory.adjust(key, UUID.randomUUID(), 0)))
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> inventory.adjust(key, UUID.randomUUID(), Long.MAX_VALUE)))
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> inventory.adjust(new InventoryKey(key.shopId(), ITEM_B, LOCATION_X),
                UUID.randomUUID(), 5))).isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
    }

    @Test
    void requestsBeyondCapacityOrUnknownInventoryNeverTouchStock() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 5000, 50);
        refills.refillOneDimension(key, false);

        assertThat(errorCodeOf(() -> reserve(key, 51))).isEqualTo(ErrorCode.QUANTITY_LIMIT_EXCEEDED);
        assertThat(errorCodeOf(() -> reserve(new InventoryKey(key.shopId(), ITEM_B, LOCATION_X), 1)))
                .isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
        assertQuantities(key, 5000, 50, 50, 0);
    }
}
