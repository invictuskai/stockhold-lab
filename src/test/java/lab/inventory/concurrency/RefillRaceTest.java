package lab.inventory.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import lab.inventory.application.FaultInjector.Point;
import lab.inventory.application.PoolRefillService.RefillResult;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** 实现与验证计划 4.3：补充竞态。交错由闩锁精确构造，不靠 sleep 猜测。 */
class RefillRaceTest extends IntegrationTestBase {

    private static final class InjectedRollback extends RuntimeException {
    }

    @Test
    void concurrentRefillsNeverOverfillOrDoubleAllocate() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 50_000, 1000);
        List<Callable<RefillResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> {
                for (int attempt = 0;; attempt++) {
                    try {
                        return refills.refillOneDimension(key, false);
                    } catch (ApiException e) {
                        if (e.code() != ErrorCode.INVENTORY_BUSY || attempt >= 20) {
                            throw e;
                        }
                    }
                }
            });
        }

        List<Outcome<RefillResult>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(Outcome::succeeded);
        // A 的增量等于新增单位数：后来者获得账本锁后重新检查池，不重复盲目补充。
        assertThat(outcomes.stream().mapToInt(o -> o.value().insertedUnits()).sum()).isEqualTo(1000);
        assertQuantities(key, 50_000, 1000, 1000, 0);
    }

    @Test
    void lockedUnitsAreBusyNotSoldOutAndCommitMakesThemTrulyGone() throws Exception {
        assertLockedUnitsAreNotReportedAsSoldOut(true);
    }

    @Test
    void lockedUnitsAreBusyNotSoldOutAndRollbackMakesThemAvailableAgain() throws Exception {
        assertLockedUnitsAreNotReportedAsSoldOut(false);
    }

    /**
     * 一个 reserve 事务锁住并删除全部池单位后停在提交前；此时另一个请求不能被告知售罄。
     * 随后分别验证该事务 commit 与 rollback 两种可见性路径。
     */
    private void assertLockedUnitsAreNotReportedAsSoldOut(boolean holderCommits) throws Exception {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 5, 1000);
        refills.refillOneDimension(key, false);
        CountDownLatch unitsLocked = new CountDownLatch(1);
        CountDownLatch finishHolder = new CountDownLatch(1);
        ExecutorService holderThread = Executors.newSingleThreadExecutor();
        try {
            Future<Reservation> holder = holderThread.submit(() -> {
                Thread self = Thread.currentThread();
                faults.install(point -> {
                    if (point == Point.RESERVE_AFTER_UNIT_DELETE && Thread.currentThread() == self) {
                        unitsLocked.countDown();
                        awaitQuietly(finishHolder);
                        if (!holderCommits) {
                            throw new InjectedRollback();
                        }
                    }
                });
                return reserve(key, 5);
            });
            assertThat(unitsLocked.await(10, TimeUnit.SECONDS)).isTrue();

            // 所有单位都被未提交事务锁住：账本锁下的 COUNT 仍看得到它们，只能是 BUSY。
            assertThat(errorCodeOf(() -> reserve(key, 1))).isEqualTo(ErrorCode.INVENTORY_BUSY);

            finishHolder.countDown();
            if (holderCommits) {
                assertThat(holder.get(10, TimeUnit.SECONDS).header().totalQuantity()).isEqualTo(5);
                assertThat(errorCodeOf(() -> reserve(key, 1))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                assertQuantities(key, 5, 5, 0, 5);
            } else {
                try {
                    holder.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException expected) {
                    assertThat(expected.getCause()).isInstanceOf(InjectedRollback.class);
                }
                assertQuantities(key, 5, 5, 5, 0);
                reserve(key, 5);
                assertQuantities(key, 5, 5, 0, 5);
            }
        } finally {
            finishHolder.countDown();
            holderThread.shutdownNow();
        }
    }

    @Test
    void refillThatCountedBeforeAConcurrentReserveOnlyUnderfills() throws Exception {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 1000, 100);
        refills.refillOneDimension(key, false);
        reserve(key, 30);
        assertQuantities(key, 1000, 100, 70, 30);

        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        ExecutorService refillThread = Executors.newSingleThreadExecutor();
        try {
            Future<RefillResult> pausedRefill = refillThread.submit(() -> {
                Thread self = Thread.currentThread();
                faults.install(point -> {
                    if (point == Point.REFILL_AFTER_COUNT && Thread.currentThread() == self) {
                        counted.countDown();
                        awaitQuietly(resume);
                    }
                });
                return refills.refillOneDimension(key, false);
            });
            assertThat(counted.await(10, TimeUnit.SECONDS)).isTrue();

            // 补充已持有账本锁并读完 COUNT=70；正常 reserve 不需要账本锁，照常提交。
            reserve(key, 20);

            resume.countDown();
            RefillResult result = pausedRefill.get(10, TimeUnit.SECONDS);
            assertThat(result.observedPoolBefore()).isEqualTo(70);
            assertThat(result.insertedUnits()).isEqualTo(30);
        } finally {
            resume.countDown();
            refillThread.shutdownNow();
        }

        // 陈旧的计数只会少填：P=50+30=80，没有超过容量 100。
        assertQuantities(key, 1000, 130, 80, 50);
    }

    @Test
    void refillRacingWithClaimCancelExpiryAndReserveKeepsLedgerBalanced() throws InterruptedException {
        InventoryKey key = createInventory(newShop(), ITEM_A, LOCATION_X, 10_000, 50);
        long shop = key.shopId();
        List<Reservation> toClaim = new ArrayList<>();
        List<Reservation> toCancel = new ArrayList<>();
        List<Reservation> toExpire = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            toClaim.add(reserve(key, 2));
            toCancel.add(reserve(key, 2));
            Reservation expiring = reserve(key, 2);
            forceExpired(expiring);
            toExpire.add(expiring);
        }

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Reservation claim = toClaim.get(i);
            Reservation cancel = toCancel.get(i);
            Reservation expire = toExpire.get(i);
            int n = i;
            tasks.add(() -> retryBusy(() -> reservations.claim(shop, claim.header().reservationId(), "pay-" + n)));
            tasks.add(() -> retryBusy(() -> reservations.cancel(shop, cancel.header().reservationId())));
            tasks.add(() -> {
                // 临时锁失败会留给下一轮扫描；这里模拟多轮直到释放。
                for (int attempt = 0; attempt < 30; attempt++) {
                    if (expiry.expireOne(shop, expire.header().reservationId())) {
                        return true;
                    }
                }
                return false;
            });
            tasks.add(() -> retryBusy(() -> refills.refillOneDimension(key, false)));
            tasks.add(() -> retryBusy(() -> reserve(key, 1)));
        }

        List<Outcome<Boolean>> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o.succeeded() && o.value());
        InventorySnapshot snapshot = inventory.snapshot(key);
        assertThat(snapshot.onHand()).isEqualTo(10_000 - 40);
        assertThat(snapshot.activeReserved()).isEqualTo(20);
        assertThat(snapshot.poolAvailable()).isLessThanOrEqualTo(50);
        assertInvariants(key);
    }

    private static boolean retryBusy(Callable<?> action) throws Exception {
        for (int attempt = 0;; attempt++) {
            try {
                action.call();
                return true;
            } catch (ApiException e) {
                if (e.code() != ErrorCode.INVENTORY_BUSY || attempt >= 30) {
                    throw e;
                }
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test latch was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
