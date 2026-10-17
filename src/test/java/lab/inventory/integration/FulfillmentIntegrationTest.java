package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** 文章：只从能履约的地点预留。候选行整行落在第一个能满足它的地点，不拆分、不越过不能履约的地点。 */
class FulfillmentIntegrationTest extends IntegrationTestBase {

    private static RawLine candidates(long itemId, int quantity, Long... locationIds) {
        return new RawLine(itemId, null, (long) quantity, List.of(locationIds));
    }

    private Reservation reserve(long shopId, String key, RawLine... lines) {
        return reservations.reserve(shopId, key, null, List.of(lines)).reservation();
    }

    @Test
    void preferredLocationIsUsedWhenItCanFulfill() {
        long shopId = newShop();
        InventoryKey x = createInventory(shopId, ITEM_A, LOCATION_X, 10, 1000);
        InventoryKey y = createInventory(shopId, ITEM_A, LOCATION_Y, 10, 1000);

        Reservation reservation = reserve(shopId, newKey("pref"), candidates(ITEM_A, 3, LOCATION_X, LOCATION_Y));

        assertThat(reservation.lines()).singleElement().satisfies(l -> {
            assertThat(l.locationId()).isEqualTo(LOCATION_X);
            assertThat(l.quantity()).isEqualTo(3);
        });
        assertThat(inventory.snapshot(x).activeReserved()).isEqualTo(3);
        assertThat(inventory.snapshot(y).activeReserved()).isZero();
    }

    @Test
    void lineFallsThroughToTheNextLocationThatCanFulfillItWhole() {
        long shopId = newShop();
        InventoryKey x = createInventory(shopId, ITEM_A, LOCATION_X, 2, 1000);
        InventoryKey y = createInventory(shopId, ITEM_A, LOCATION_Y, 10, 1000);

        Reservation reservation = reserve(shopId, newKey("fallback"), candidates(ITEM_A, 3, LOCATION_X, LOCATION_Y));

        assertThat(reservation.lines()).singleElement().satisfies(l -> assertThat(l.locationId()).isEqualTo(LOCATION_Y));
        // 首选地点只有 2 件：不拆分，也不留下任何占用。
        assertQuantities(x, 2, 2, 2, 0);
        assertThat(inventory.snapshot(y).activeReserved()).isEqualTo(3);
        assertInvariants(y);
    }

    @Test
    void uninitializedCandidateIsSkipped() {
        long shopId = newShop();
        createInventory(shopId, ITEM_A, LOCATION_Y, 5, 1000);

        Reservation reservation = reserve(shopId, newKey("skip"), candidates(ITEM_A, 1, LOCATION_X, LOCATION_Y));

        assertThat(reservation.lines().get(0).locationId()).isEqualTo(LOCATION_Y);
    }

    @Test
    void neverSplitsAcrossLocationsEvenIfTheirSumWouldSuffice() {
        long shopId = newShop();
        InventoryKey x = createInventory(shopId, ITEM_A, LOCATION_X, 2, 1000);
        InventoryKey y = createInventory(shopId, ITEM_A, LOCATION_Y, 2, 1000);

        assertThat(errorCodeOf(() -> reserve(shopId, newKey("split"), candidates(ITEM_A, 3, LOCATION_X, LOCATION_Y))))
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertQuantities(x, 2, 2, 2, 0);
        assertQuantities(y, 2, 2, 2, 0);
    }

    @Test
    void mixedCartStaysAtomicWhenTheFixedLineCannotBeFulfilled() {
        long shopId = newShop();
        InventoryKey fixed = createInventory(shopId, ITEM_A, LOCATION_X, 1, 1000);
        InventoryKey x = createInventory(shopId, ITEM_B, LOCATION_X, 10, 1000);
        createInventory(shopId, ITEM_B, LOCATION_Y, 10, 1000);

        assertThat(errorCodeOf(() -> reserve(shopId, newKey("mixed"), line(fixed, 2),
                candidates(ITEM_B, 1, LOCATION_X, LOCATION_Y)))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        assertThat(inventory.snapshot(x).activeReserved()).isZero();

        Reservation ok = reserve(shopId, newKey("mixed-ok"), line(fixed, 1), candidates(ITEM_B, 4, LOCATION_X));
        assertThat(ok.lines()).hasSize(2);
        assertThat(inventory.snapshot(x).activeReserved()).isEqualTo(4);
    }

    @Test
    void replayReturnsTheSameChoiceAndADifferentCandidateOrderConflicts() {
        long shopId = newShop();
        createInventory(shopId, ITEM_A, LOCATION_X, 2, 1000);
        InventoryKey y = createInventory(shopId, ITEM_A, LOCATION_Y, 10, 1000);
        String key = newKey("replay");

        Reservation first = reserve(shopId, key, candidates(ITEM_A, 3, LOCATION_X, LOCATION_Y));
        Reservation replay = reserve(shopId, key, candidates(ITEM_A, 3, LOCATION_X, LOCATION_Y));

        assertThat(replay.header().reservationId()).isEqualTo(first.header().reservationId());
        assertThat(inventory.snapshot(y).activeReserved()).isEqualTo(3);
        assertThat(errorCodeOf(() -> reserve(shopId, key, candidates(ITEM_A, 3, LOCATION_Y, LOCATION_X))))
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        // 同一请求改用固定地点也是不同意图。
        assertThat(errorCodeOf(() -> reserve(shopId, key, line(y, 3)))).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void candidateListIsValidated() {
        long shopId = newShop();
        assertThat(errorCodeOf(() -> reserve(shopId, newKey("both"),
                new RawLine(ITEM_A, LOCATION_X, 1L, List.of(LOCATION_Y))))).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> reserve(shopId, newKey("empty"), new RawLine(ITEM_A, null, 1L, List.of()))))
                .isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(errorCodeOf(() -> reserve(shopId, newKey("many"),
                candidates(ITEM_A, 1, 1L, 2L, 3L, 4L, 5L, 6L)))).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }
}
