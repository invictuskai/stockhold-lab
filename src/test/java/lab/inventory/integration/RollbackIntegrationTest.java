package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import lab.inventory.application.FaultInjector.Point;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** 实现与验证计划 4.5：在事务中间可控抛异常，断言头、单位、明细、账本全部回滚。 */
class RollbackIntegrationTest extends IntegrationTestBase {

    private static final class InjectedFault extends RuntimeException {
        private InjectedFault(Point point) {
            super("injected at " + point);
        }
    }

    private void failAt(Point target) {
        faults.install(point -> {
            if (point == target) {
                throw new InjectedFault(point);
            }
        });
    }

    @Test
    void reserveRollsBackAfterUnitsWereDeleted() {
        assertReserveRollsBackAt(Point.RESERVE_AFTER_UNIT_DELETE);
    }

    @Test
    void reserveRollsBackAfterSomeLinesWereInserted() {
        assertReserveRollsBackAt(Point.RESERVE_AFTER_FIRST_LINE);
    }

    private void assertReserveRollsBackAt(Point point) {
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 10, 1000);
        refills.refillOneDimension(a, false);
        refills.refillOneDimension(b, false);
        String idempotencyKey = newKey("fault");
        failAt(point);

        assertThatThrownBy(() -> reservations.reserve(a.shopId(), idempotencyKey, null,
                List.of(line(a, 3), line(b, 2)))).isInstanceOf(InjectedFault.class);
        faults.clear();

        // 没有残留的 ACTIVE 头 / 明细，单位没有丢失，幂等键未被占用。
        assertQuantities(a, 10, 10, 10, 0);
        assertQuantities(b, 10, 10, 10, 0);
        assertThat(errorCodeOf(() -> reservations.getByIdempotencyKey(a.shopId(), idempotencyKey)))
                .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        assertThat(countRows("reserved_quantities", a.shopId())).isZero();

        // 同一个幂等键之后可以正常成功。
        assertThat(reservations.reserve(a.shopId(), idempotencyKey, null, List.of(line(a, 3), line(b, 2)))
                .replayed()).isFalse();
        assertQuantities(a, 10, 10, 7, 3);
        assertQuantities(b, 10, 10, 8, 2);
    }

    @Test
    void claimRollsBackLedgerWhenHeaderUpdateNeverHappens() {
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 10, 1000);
        Reservation reservation = reservations.reserve(a.shopId(), newKey("fault"), null,
                List.of(line(a, 3), line(b, 2))).reservation();
        failAt(Point.CLAIM_AFTER_LEDGER_UPDATE);

        assertThatThrownBy(() -> reservations.claim(a.shopId(), reservation.header().reservationId(), "pay-1"))
                .isInstanceOf(InjectedFault.class);
        faults.clear();

        assertThat(reservations.get(a.shopId(), reservation.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertQuantities(a, 10, 10, 7, 3);
        assertQuantities(b, 10, 10, 8, 2);

        reservations.claim(a.shopId(), reservation.header().reservationId(), "pay-1");
        assertQuantities(a, 7, 7, 7, 0);
        assertQuantities(b, 8, 8, 8, 0);
    }

    @Test
    void cancelAndExpiryRollBackLedgerWhenHeaderUpdateNeverHappens() {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        Reservation toCancel = reserve(key, 3);
        Reservation toExpire = reserve(key, 2);
        forceExpired(toExpire);
        failAt(Point.RELEASE_AFTER_LEDGER_UPDATE);

        assertThatThrownBy(() -> reservations.cancel(key.shopId(), toCancel.header().reservationId()))
                .isInstanceOf(InjectedFault.class);
        // 到期任务把单条失败留给下一轮，不向上抛出。
        assertThat(expiry.expireOne(key.shopId(), toExpire.header().reservationId())).isFalse();
        faults.clear();

        assertQuantities(key, 10, 10, 5, 5);

        reservations.cancel(key.shopId(), toCancel.header().reservationId());
        assertThat(expiry.expireOne(key.shopId(), toExpire.header().reservationId())).isTrue();
        assertQuantities(key, 10, 5, 5, 0);
    }

    @Test
    void refillRollsBackAllocationWhenUnitsWereNotInserted() {
        assertRefillRollsBackAt(Point.REFILL_AFTER_ALLOCATED_UPDATE);
    }

    @Test
    void refillRollsBackEarlierBatchesWhenALaterBatchFails() {
        assertRefillRollsBackAt(Point.REFILL_AFTER_FIRST_BATCH);
    }

    private void assertRefillRollsBackAt(Point point) {
        // 容量 250、批次 100：补充跨越多个 INSERT 批次。
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 250);
        failAt(point);

        assertThatThrownBy(() -> refills.refillOneDimension(key, false)).isInstanceOf(InjectedFault.class);
        faults.clear();

        assertQuantities(key, 1000, 0, 0, 0);
        assertThat(refills.refillOneDimension(key, false).insertedUnits()).isEqualTo(250);
        assertQuantities(key, 1000, 250, 250, 0);
    }

    @Test
    void multiItemReservationIsAtomicWhenOneDimensionIsShort() {
        // A 足量、B 不足：失败后 A 的单位没有丢失。
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 10, 1000);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 1, 1000);
        refills.refillOneDimension(a, false);
        refills.refillOneDimension(b, false);

        assertThat(errorCodeOf(() -> reservations.reserve(a.shopId(), newKey("atomic"), null,
                List.of(line(a, 3), line(b, 2))))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        assertQuantities(a, 10, 10, 10, 0);
        assertQuantities(b, 1, 1, 1, 0);
        assertThat(countRows("reservations", a.shopId())).isZero();
    }

    @Test
    void otherLocationIsNeverBorrowed() {
        // locationX 售罄不能消费 locationY，除非请求显式指定。
        InventoryKey x = createInventory(newShop(), ITEM_A, LOCATION_X, 1, 1000);
        InventoryKey y = createInventory(x.shopId(), ITEM_A, LOCATION_Y, 5, 1000);
        reserve(x, 1);

        assertThat(errorCodeOf(() -> reserve(x, 1))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertQuantities(y, 5, 0, 0, 0);

        reserve(y, 1);
        assertQuantities(x, 1, 1, 0, 1);
        assertQuantities(y, 5, 5, 4, 1);
    }

    private long countRows(String table, long shopId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE shop_id = ?", Long.class, shopId);
        return count == null ? 0 : count;
    }
}
