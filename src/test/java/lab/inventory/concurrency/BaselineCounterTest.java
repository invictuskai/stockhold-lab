package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.benchmark.BaselineCounterService;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/** 单计数器对照组必须与单位池方案具有等价的幂等、状态机与失败语义，对比才有意义。 */
@ActiveProfiles({"test", "benchmark"})
class BaselineCounterTest extends IntegrationTestBase {

    @Autowired
    private BaselineCounterService baseline;

    @Test
    void counterNeverOversellsUnderContention() throws InterruptedException {
        InventoryKey key = new InventoryKey(newShop(), ITEM_A, LOCATION_X);
        baseline.seed(key, 50);
        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> baseline.reserve(key.shopId(), newKey("bl"), null, List.of(line(key, 1))));
        }

        List<Outcome<ReserveOutcome>> outcomes = runConcurrently(tasks);

        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::code).collect(Collectors.toSet()))
                .isSubsetOf(ErrorCode.INSUFFICIENT_STOCK, ErrorCode.INVENTORY_BUSY);
        assertThat(succeeded).isLessThanOrEqualTo(50);
        assertThat(baseline.counter(key)).isEqualTo(new BaselineCounterService.CounterRow(50, succeeded));
    }

    @Test
    void lifecycleAndIdempotencyMatchTheUnitPoolSemantics() {
        InventoryKey key = new InventoryKey(newShop(), ITEM_A, LOCATION_X);
        long shop = key.shopId();
        baseline.seed(key, 10);
        String idempotencyKey = newKey("bl");

        ReserveOutcome created = baseline.reserve(shop, idempotencyKey, null, List.of(line(key, 3)));
        ReserveOutcome replay = baseline.reserve(shop, idempotencyKey, null, List.of(line(key, 3)));
        assertThat(created.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(errorCodeOf(() -> baseline.reserve(shop, idempotencyKey, null, List.of(line(key, 4)))))
                .isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(baseline.counter(key)).isEqualTo(new BaselineCounterService.CounterRow(10, 3));

        Reservation toCancel = baseline.reserve(shop, newKey("bl"), null, List.of(line(key, 2))).reservation();
        assertThat(errorCodeOf(() -> baseline.reserve(shop, newKey("bl"), null, List.of(line(key, 6)))))
                .isEqualTo(ErrorCode.INSUFFICIENT_STOCK);

        Reservation claimed = baseline.claim(shop, created.reservation().header().reservationId(), "bl-pay-1");
        assertThat(claimed.header().status()).isEqualTo(ReservationStatus.CLAIMED);
        assertThat(baseline.claim(shop, claimed.header().reservationId(), "bl-pay-1").header().status())
                .isEqualTo(ReservationStatus.CLAIMED);
        assertThat(errorCodeOf(() -> baseline.claim(shop, toCancel.header().reservationId(), "bl-pay-1")))
                .isEqualTo(ErrorCode.PAYMENT_REFERENCE_CONFLICT);
        assertThat(baseline.counter(key)).isEqualTo(new BaselineCounterService.CounterRow(7, 2));

        assertThat(baseline.cancel(shop, toCancel.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(baseline.cancel(shop, toCancel.header().reservationId()).header().status())
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(errorCodeOf(() -> baseline.cancel(shop, claimed.header().reservationId())))
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATE);
        assertThat(baseline.counter(key)).isEqualTo(new BaselineCounterService.CounterRow(7, 0));

        assertThat(errorCodeOf(() -> baseline.reserve(shop, newKey("bl"), null,
                List.of(line(new InventoryKey(shop, ITEM_B, LOCATION_X), 1)))))
                .isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
    }
}
