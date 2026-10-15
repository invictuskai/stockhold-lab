package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** 实现与验证计划 4.2：核心争抢场景。 */
class ReserveContentionTest extends IntegrationTestBase {

    @Test
    void lastUnitIsSoldExactlyOnce() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1, 1000);
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> reserve(key, 1));
        }

        List<Outcome<Reservation>> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
        // 失败者只能是真实不足或临时繁忙，不能出现其他错误。
        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::code).collect(Collectors.toSet()))
                .isSubsetOf(ErrorCode.INSUFFICIENT_STOCK, ErrorCode.INVENTORY_BUSY);
        assertQuantities(key, 1, 1, 0, 1);
    }

    @Test
    void activeReservationsCanExceedPoolCapacityWhilePoolStaysBounded() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10_000, 1000);
        int threads = 32;
        int perThread = 50;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                int succeeded = 0;
                for (int i = 0; i < perThread; i++) {
                    try {
                        reserve(key, 1);
                        succeeded++;
                    } catch (ApiException e) {
                        // 库存充足时唯一允许的失败是可重试的繁忙。
                        assertThat(e.code()).isEqualTo(ErrorCode.INVENTORY_BUSY);
                    }
                    assertThat(inventory.snapshot(key).poolAvailable()).isLessThanOrEqualTo(1000);
                }
                return succeeded;
            });
        }

        List<Outcome<Integer>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        long succeeded = outcomes.stream().mapToInt(Outcome::value).sum();
        InventorySnapshot snapshot = inventory.snapshot(key);
        assertThat(snapshot.activeReserved()).isEqualTo(succeeded).isGreaterThan(1000);
        assertThat(snapshot.poolAvailable()).isLessThanOrEqualTo(1000);
        assertThat(snapshot.onHand()).isEqualTo(10_000);
        assertInvariants(key);
    }

    @Test
    void hotItemNeverOversellsAndLeftoverIsNotInflated() throws InterruptedException {
        // H=1000，每次预留 3：最多 333 次成功，剩余的 1 不会被虚构成 3。
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 1000);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < 24; t++) {
            tasks.add(() -> {
                int succeeded = 0;
                for (int i = 0; i < 400; i++) {
                    try {
                        reserve(key, 3);
                        succeeded++;
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.INSUFFICIENT_STOCK) {
                            break;
                        }
                        assertThat(e.code()).isEqualTo(ErrorCode.INVENTORY_BUSY);
                    }
                }
                return succeeded;
            });
        }

        List<Outcome<Integer>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().mapToInt(Outcome::value).sum()).isEqualTo(333);
        InventorySnapshot snapshot = inventory.snapshot(key);
        assertThat(snapshot.activeReserved()).isEqualTo(999);
        assertThat(snapshot.effectiveAvailable()).isEqualTo(1);
        assertInvariants(key);
    }

    @Test
    void oppositeInputOrdersUseOneLockOrder() throws InterruptedException {
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 5000, 200);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 5000, 200);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int t = 0; t < 24; t++) {
            boolean forward = t % 2 == 0;
            tasks.add(() -> {
                int succeeded = 0;
                for (int i = 0; i < 25; i++) {
                    try {
                        reservations.reserve(a.shopId(), newKey("order"), null,
                                forward ? List.of(line(a, 2), line(b, 3)) : List.of(line(b, 3), line(a, 2)));
                        succeeded++;
                    } catch (ApiException e) {
                        assertThat(e.code()).isEqualTo(ErrorCode.INVENTORY_BUSY);
                    }
                }
                return succeeded;
            });
        }

        List<Outcome<Integer>> outcomes = runConcurrently(tasks);

        // 任何未被归类的数据库异常（例如泄漏出来的死锁）都会让任务失败。
        assertThat(outcomes).allMatch(Outcome::succeeded);
        long succeeded = outcomes.stream().mapToInt(Outcome::value).sum();
        assertThat(succeeded).isPositive();
        assertThat(inventory.snapshot(a).activeReserved()).isEqualTo(succeeded * 2);
        assertThat(inventory.snapshot(b).activeReserved()).isEqualTo(succeeded * 3);
        assertInvariants(a);
        assertInvariants(b);
    }

    @Test
    void multiItemCartsStayAtomicUnderContention() throws InterruptedException {
        // B 只有 10 件：每个购物车要 A×1 + B×1，成功数恰为 10，A 不会被多扣。
        InventoryKey a = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 100);
        InventoryKey b = createInventory(a.shopId(), ITEM_B, LOCATION_X, 10, 100);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int t = 0; t < 40; t++) {
            tasks.add(() -> {
                for (int attempt = 0; attempt < 20; attempt++) {
                    try {
                        reservations.reserve(a.shopId(), newKey("cart"), null, List.of(line(a, 1), line(b, 1)));
                        return true;
                    } catch (ApiException e) {
                        if (e.code() == ErrorCode.INSUFFICIENT_STOCK) {
                            return false;
                        }
                        assertThat(e.code()).isEqualTo(ErrorCode.INVENTORY_BUSY);
                    }
                }
                return false;
            });
        }

        List<Outcome<Boolean>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().filter(Outcome::value)).hasSize(10);
        assertThat(inventory.snapshot(a).activeReserved()).isEqualTo(10);
        assertThat(inventory.snapshot(b).activeReserved()).isEqualTo(10);
        assertThat(inventory.snapshot(a).effectiveAvailable()).isEqualTo(990);
        assertInvariants(a);
        assertInvariants(b);
    }

    @Test
    void concurrentRequestsWithOneIdempotencyKeyConvergeOnOneReservation() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 1000);
        refills.refillOneDimension(key, false);
        String idempotencyKey = newKey("same");
        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            tasks.add(() -> {
                // 客户端契约：可重试错误沿用原幂等键重试，最终收敛。
                for (int attempt = 0;; attempt++) {
                    try {
                        return reservations.reserve(key.shopId(), idempotencyKey, null, List.of(line(key, 2)));
                    } catch (ApiException e) {
                        if (e.code() != ErrorCode.INVENTORY_BUSY || attempt >= 10) {
                            throw e;
                        }
                    }
                }
            });
        }

        List<Outcome<ReserveOutcome>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        Set<Object> ids = outcomes.stream()
                .map(o -> o.value().reservation().header().reservationId()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed())).hasSize(1);
        assertQuantities(key, 1000, 1000, 998, 2);
    }

    @Test
    void sameKeyWithDifferentRequestsNeverCreatesTwoReservations() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 1000);
        refills.refillOneDimension(key, false);
        String idempotencyKey = newKey("conflict");
        List<Callable<ReserveOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int quantity = 1 + i % 2;
            tasks.add(() -> reservations.reserve(key.shopId(), idempotencyKey, null,
                    List.of(line(key, quantity))));
        }

        List<Outcome<ReserveOutcome>> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> !o.succeeded()).map(Outcome::code).collect(Collectors.toSet()))
                .isSubsetOf(ErrorCode.IDEMPOTENCY_CONFLICT, ErrorCode.INVENTORY_BUSY);
        Reservation winner = reservations.getByIdempotencyKey(key.shopId(), idempotencyKey);
        int quantity = winner.header().totalQuantity();
        assertQuantities(key, 1000, 1000, 1000 - quantity, quantity);
    }
}
