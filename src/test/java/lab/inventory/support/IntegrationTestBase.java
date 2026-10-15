package lab.inventory.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import lab.inventory.application.ExpiryService;
import lab.inventory.application.FaultInjector;
import lab.inventory.application.InvariantService;
import lab.inventory.application.InventoryService;
import lab.inventory.application.PoolRefillService;
import lab.inventory.application.ReservationFacade;
import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实 MySQL 上的集成测试基类。每个测试使用自己的随机 shop，互不清理、互不干扰。 */
@ExtendWith(MySqlTestDatabase.class)
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    private static final AtomicLong SHOP_SEQUENCE =
            new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000_000L, 1_000_000_000_000L));

    protected static final long ITEM_A = 2001;
    protected static final long ITEM_B = 2002;
    protected static final long LOCATION_X = 3001;
    protected static final long LOCATION_Y = 3002;

    @Autowired
    protected InventoryService inventory;
    @Autowired
    protected ReservationFacade reservations;
    @Autowired
    protected PoolRefillService refills;
    @Autowired
    protected ExpiryService expiry;
    @Autowired
    protected InvariantService invariants;
    @Autowired
    protected FaultInjector faults;
    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        MySqlTestDatabase.Coordinates db = MySqlTestDatabase.resolve();
        if (db != null) {
            registry.add("spring.datasource.url", db::url);
            registry.add("spring.datasource.username", db::username);
            registry.add("spring.datasource.password", db::password);
        }
        // 固定测试令牌：开发者 shell 中的 STOCKHOLD_ADMIN_TOKEN 优先级高于 application-test.yml。
        registry.add("stockhold.admin.token", () -> "test-token");
    }

    @AfterEach
    void clearFaults() {
        faults.clear();
    }

    protected static long newShop() {
        return SHOP_SEQUENCE.incrementAndGet();
    }

    protected InventoryKey createInventory(long shopId, long itemId, long locationId, long onHand, int capacity) {
        InventoryKey key = new InventoryKey(shopId, itemId, locationId);
        inventory.create(key, UUID.randomUUID(), onHand, capacity);
        return key;
    }

    protected static String newKey(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    protected Reservation reserve(InventoryKey key, int quantity) {
        return reservations.reserve(key.shopId(), newKey("test"), null,
                List.of(new RawLine(key.inventoryItemId(), key.locationId(), (long) quantity))).reservation();
    }

    protected static RawLine line(InventoryKey key, int quantity) {
        return new RawLine(key.inventoryItemId(), key.locationId(), (long) quantity);
    }

    /** 测试专用：把预留的到期时间改到过去，代替真实等待 TTL。 */
    protected void forceExpired(Reservation reservation) {
        jdbc.update("UPDATE reservations SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND"
                + " WHERE shop_id = ? AND reservation_id = ?",
                reservation.header().shopId(),
                lab.inventory.persistence.Codec.uuidToBytes(reservation.header().reservationId()));
    }

    protected void assertQuantities(InventoryKey key, long onHand, long allocated, long pool, long active) {
        InventorySnapshot s = inventory.snapshot(key);
        assertThat(List.of(s.onHand(), s.allocated(), s.poolAvailable(), s.activeReserved()))
                .as("H, A, P, R of %s", key)
                .containsExactly(onHand, allocated, pool, active);
        assertInvariants(key);
    }

    protected void assertInvariants(InventoryKey key) {
        InvariantService.Report report = invariants.check(key);
        assertThat(report.violations()).as("invariants of %s", key).isEmpty();
    }

    protected static ErrorCode errorCodeOf(Callable<?> action) {
        try {
            action.call();
        } catch (ApiException e) {
            return e.code();
        } catch (Exception e) {
            throw new AssertionError("Expected ApiException but got " + e, e);
        }
        throw new AssertionError("Expected ApiException but the call succeeded");
    }

    /** 并发执行结果：成功值或抛出的异常，二者必有其一。 */
    public record Outcome<T>(T value, Throwable error) {

        public boolean succeeded() {
            return error == null;
        }

        public ErrorCode code() {
            return error instanceof ApiException api ? api.code() : null;
        }
    }

    /** 用同一个起跑闩同时放行所有任务，而不是靠 sleep 猜测交错。 */
    protected static <T> List<Outcome<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable e) {
                        return new Outcome<T>(null, e);
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                try {
                    outcomes.add(future.get(120, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError("Concurrent task did not finish", e);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
