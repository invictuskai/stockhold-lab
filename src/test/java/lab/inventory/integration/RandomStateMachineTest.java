package lab.inventory.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;

import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * 实现与验证计划 4.6：随机状态机。单线程阶段每一步与内存参考模型对照；
 * 并发阶段在静止检查点核对全量不变量与审计总量。失败时输出随机种子与操作日志。
 */
class RandomStateMachineTest extends IntegrationTestBase {

    private static final int CAPACITY = 20;

    /** 参考模型：每个维度的 H，以及每个 ACTIVE 预留占用的数量。 */
    private static final class Model {
        final Map<InventoryKey, Long> onHand = new HashMap<>();
        final Map<UUID, Map<InventoryKey, Integer>> active = new HashMap<>();

        long reserved(InventoryKey key) {
            return active.values().stream().mapToLong(lines -> lines.getOrDefault(key, 0)).sum();
        }
    }

    @Test
    void singleThreadedRandomOperationsMatchTheReferenceModel() {
        long seed = Long.getLong("stockhold.test.seed", System.nanoTime());
        Random random = new Random(seed);
        List<String> log = new ArrayList<>();
        long shop = newShop();
        List<InventoryKey> keys = List.of(
                createInventory(shop, ITEM_A, LOCATION_X, 60, CAPACITY),
                createInventory(shop, ITEM_B, LOCATION_X, 60, CAPACITY));
        Model model = new Model();
        keys.forEach(key -> model.onHand.put(key, 60L));
        int paymentSequence = 0;

        try {
            for (int step = 0; step < 300; step++) {
                InventoryKey key = keys.get(random.nextInt(keys.size()));
                switch (random.nextInt(8)) {
                    case 0 -> {
                        int delta = 1 + random.nextInt(20);
                        log.add("restock " + key.inventoryItemId() + " +" + delta);
                        inventory.adjust(key, UUID.randomUUID(), delta);
                        model.onHand.merge(key, (long) delta, Long::sum);
                    }
                    case 1 -> {
                        int delta = 1 + random.nextInt(10);
                        log.add("shrink " + key.inventoryItemId() + " -" + delta);
                        long allocated = inventory.snapshot(key).allocated();
                        boolean allowed = model.onHand.get(key) - delta >= allocated;
                        try {
                            inventory.adjust(key, UUID.randomUUID(), -delta);
                            assertThat(allowed).as("shrink below allocated must be rejected").isTrue();
                            model.onHand.merge(key, (long) -delta, Long::sum);
                        } catch (ApiException e) {
                            assertThat(e.code()).isEqualTo(ErrorCode.STOCK_ALLOCATED);
                            assertThat(allowed).isFalse();
                        }
                    }
                    case 2, 3, 4 -> {
                        Map<InventoryKey, Integer> wanted = new HashMap<>();
                        wanted.put(key, 1 + random.nextInt(CAPACITY));
                        if (random.nextBoolean()) {
                            wanted.put(keys.get(random.nextInt(keys.size())), 1 + random.nextInt(CAPACITY));
                        }
                        log.add("reserve " + wanted);
                        boolean fits = wanted.entrySet().stream()
                                .allMatch(e -> e.getValue() <= model.onHand.get(e.getKey()) - model.reserved(e.getKey()));
                        List<RawLine> lines = wanted.entrySet().stream()
                                .map(e -> line(e.getKey(), e.getValue())).toList();
                        try {
                            Reservation reservation = reservations.reserve(shop, newKey("rsm"), null, lines)
                                    .reservation();
                            assertThat(fits).as("reserve beyond H - R must fail").isTrue();
                            model.active.put(reservation.header().reservationId(), wanted);
                        } catch (ApiException e) {
                            // 单线程下没有锁竞争：失败只能是真实不足。
                            assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                            assertThat(fits).isFalse();
                        }
                    }
                    case 5 -> {
                        UUID id = pick(model, random);
                        if (id != null) {
                            log.add("claim " + id);
                            reservations.claim(shop, id, "rsm-pay-" + shop + "-" + paymentSequence++);
                            model.active.remove(id).forEach((k, n) -> model.onHand.merge(k, (long) -n, Long::sum));
                        }
                    }
                    case 6 -> {
                        UUID id = pick(model, random);
                        if (id != null) {
                            boolean viaExpiry = random.nextBoolean();
                            log.add((viaExpiry ? "expire " : "cancel ") + id);
                            if (viaExpiry) {
                                forceExpired(reservations.get(shop, id));
                                assertThat(expiry.expireOne(shop, id)).isTrue();
                            } else {
                                reservations.cancel(shop, id);
                            }
                            model.active.remove(id);
                        }
                    }
                    default -> {
                        log.add("refill " + key.inventoryItemId());
                        refills.refillOneDimension(key, false);
                    }
                }
                for (InventoryKey each : keys) {
                    InventorySnapshot snapshot = inventory.snapshot(each);
                    assertThat(snapshot.onHand()).as("H of %s at step %d", each, step)
                            .isEqualTo(model.onHand.get(each));
                    assertThat(snapshot.activeReserved()).as("R of %s at step %d", each, step)
                            .isEqualTo(model.reserved(each));
                    assertInvariants(each);
                }
            }
        } catch (Throwable failure) {
            throw new AssertionError("Random state machine failed; rerun with -Dstockhold.test.seed=" + seed
                    + "\nlast operations: " + log.subList(Math.max(0, log.size() - 15), log.size()), failure);
        }
    }

    @Test
    void concurrentRandomOperationsKeepInvariantsAtQuiescence() throws InterruptedException {
        long seed = Long.getLong("stockhold.test.seed", System.nanoTime());
        long shop = newShop();
        List<InventoryKey> keys = List.of(
                createInventory(shop, ITEM_A, LOCATION_X, 400, CAPACITY),
                createInventory(shop, ITEM_B, LOCATION_X, 400, CAPACITY),
                createInventory(shop, ITEM_A, LOCATION_Y, 400, CAPACITY));
        List<Callable<Integer>> workers = new ArrayList<>();
        for (int worker = 0; worker < 12; worker++) {
            Random random = new Random(seed + worker);
            int workerId = worker;
            workers.add(() -> {
                List<UUID> mine = new ArrayList<>();
                int unexpected = 0;
                for (int step = 0; step < 60; step++) {
                    InventoryKey key = keys.get(random.nextInt(keys.size()));
                    try {
                        switch (random.nextInt(6)) {
                            case 0, 1, 2 -> mine.add(reservations.reserve(shop, newKey("crsm"), null, List.of(
                                    line(key, 1 + random.nextInt(5)),
                                    line(keys.get(random.nextInt(keys.size())), 1 + random.nextInt(5))))
                                    .reservation().header().reservationId());
                            case 3 -> {
                                if (!mine.isEmpty()) {
                                    reservations.claim(shop, mine.remove(random.nextInt(mine.size())),
                                            "crsm-" + shop + "-" + workerId + "-" + step);
                                }
                            }
                            case 4 -> {
                                if (!mine.isEmpty()) {
                                    reservations.cancel(shop, mine.remove(random.nextInt(mine.size())));
                                }
                            }
                            default -> {
                                if (random.nextBoolean()) {
                                    refills.refillOneDimension(key, false);
                                } else {
                                    inventory.adjust(key, UUID.randomUUID(), 1 + random.nextInt(10));
                                }
                            }
                        }
                    } catch (ApiException e) {
                        if (e.code() != ErrorCode.INVENTORY_BUSY && e.code() != ErrorCode.INSUFFICIENT_STOCK) {
                            unexpected++;
                        }
                    }
                }
                return unexpected;
            });
        }

        List<Outcome<Integer>> outcomes = runConcurrently(workers);

        assertThat(outcomes).as("seed %d", seed).allMatch(o -> o.succeeded() && o.value() == 0);
        // 静止检查点：A = P + R、P <= C、无孤儿明细，且 管理操作 delta - CLAIMED = H。
        for (InventoryKey key : keys) {
            assertThat(invariants.check(key).violations()).as("seed %d, %s", seed, key).isEmpty();
        }
    }

    private static UUID pick(Model model, Random random) {
        if (model.active.isEmpty()) {
            return null;
        }
        List<UUID> ids = new ArrayList<>(model.active.keySet());
        ids.sort(null);
        return ids.get(random.nextInt(ids.size()));
    }
}
