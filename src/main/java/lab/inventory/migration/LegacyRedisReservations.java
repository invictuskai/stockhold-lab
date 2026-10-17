package lab.inventory.migration;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import lab.inventory.application.FaultInjector;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 被替换的旧系统（文章：每个商品一个数量键，预留 DECR、释放 INCR；预留在 Redis，账本在 MySQL）。
 * Redis 中每个维度有可售计数与已预留计数，健康时恒有 可售 + 已预留 = 旧账本的账面库存。
 * Redis 内的每一步都是一个 Lua 脚本，保证单个 Redis 内的多维度原子性——这是旧方案能做到的最好情况；
 * 但确认要同时改 Redis 与 MySQL 账本，两步无法原子提交，这正是文章迁移的根本原因（见 {@link #claim}）。
 * 键都带 {shopId} 哈希标签，同一店铺的键落在同一个槽位。
 */
@Component
@Profile("migration")
public class LegacyRedisReservations {

    private static final Logger log = LoggerFactory.getLogger(LegacyRedisReservations.class);
    private static final String SHOPS = "sh:migration:shops";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(new ClassPathResource("migration/reserve.lua"),
            List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM = RedisScript.of(new ClassPathResource("migration/claim.lua"),
            List.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RELEASE = RedisScript.of(new ClassPathResource("migration/release.lua"),
            List.class);

    /** 旧系统确认的两步顺序。文章：取决于顺序，会出现超卖或少卖。 */
    public enum ClaimOrder {
        /** 先改 Redis 状态再扣 MySQL 账本；中间失败 = 已售出但账本未扣减（账面虚高，按账本重算后超卖）。 */
        REDIS_FIRST,
        /** 先扣 MySQL 账本再改 Redis 状态；中间失败 = 已扣减但仍标记为预留（同一件被算两次，少卖）。 */
        LEDGER_FIRST
    }

    /** 某维度在旧系统中的账实差异；healthy 时 drift = 0。 */
    public record LegacyDrift(InventoryKey key, long ledgerOnHand, long redisAvailable, long redisReserved) {

        /** 可售 + 已预留 − 账面。为负：账本多算（已售未扣，超卖风险）；为正：同一件被重复计入（少卖）。 */
        public long drift() {
            return redisAvailable + redisReserved - ledgerOnHand;
        }
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final FaultInjector faults;
    private final int sweepBatchSize;
    private final ClaimOrder claimOrder;

    public LegacyRedisReservations(StringRedisTemplate redis, JdbcTemplate jdbc, DataSource dataSource,
            FaultInjector faults, @Value("${stockhold.migration.sweep-batch-size:200}") int sweepBatchSize,
            @Value("${stockhold.migration.legacy-claim-order:REDIS_FIRST}") ClaimOrder claimOrder) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.faults = faults;
        this.sweepBatchSize = sweepBatchSize;
        this.claimOrder = claimOrder;
        new ResourceDatabasePopulator(new ClassPathResource("db/migration-lab/legacy_ledger.sql")).execute(dataSource);
    }

    static String reserved(InventoryKey key) {
        return "sh:{" + key.shopId() + "}:resv:" + key.inventoryItemId() + ":" + key.locationId();
    }

    static String available(InventoryKey key) {
        return "sh:{" + key.shopId() + "}:avail:" + key.inventoryItemId() + ":" + key.locationId();
    }

    static String reservation(long shopId, String idempotencyKey) {
        return "sh:{" + shopId + "}:res:" + idempotencyKey;
    }

    private static String expiry(long shopId) {
        return "sh:{" + shopId + "}:exp";
    }

    private static String paymentReferences(long shopId) {
        return "sh:{" + shopId + "}:payrefs";
    }

    /** 初始化（或重置）一个维度：旧账本的账面库存，以及由它同步进 Redis 的可售计数。 */
    public void seed(InventoryKey key, long available) {
        jdbc.update("INSERT INTO legacy_inventory_ledger (shop_id, inventory_item_id, location_id, on_hand_quantity)"
                + " VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE on_hand_quantity = VALUES(on_hand_quantity)",
                key.shopId(), key.inventoryItemId(), key.locationId(), available);
        redis.opsForValue().set(available(key), Long.toString(available));
        redis.opsForValue().set(reserved(key), "0");
        redis.opsForSet().add(SHOPS, Long.toString(key.shopId()));
    }

    /**
     * 旧系统的对账：以 MySQL 账本为准重算 Redis 可售计数（可售 = 账面 − 已预留）。
     * 账实一致时不改变任何值；存在分歧时，它把分歧变成真实的超卖或少卖。
     */
    public void resyncFromLedger(InventoryKey key) {
        LegacyDrift drift = drift(key);
        redis.opsForValue().set(available(key), Long.toString(drift.ledgerOnHand() - drift.redisReserved()));
    }

    public LegacyDrift drift(InventoryKey key) {
        Long ledger = jdbc.query("SELECT on_hand_quantity FROM legacy_inventory_ledger WHERE shop_id = ?"
                + " AND inventory_item_id = ? AND location_id = ?", rs -> rs.next() ? rs.getLong(1) : null,
                key.shopId(), key.inventoryItemId(), key.locationId());
        String reservedCount = redis.opsForValue().get(reserved(key));
        Long availableCount = availableCount(key);
        return new LegacyDrift(key, ledger == null ? 0 : ledger, availableCount == null ? 0 : availableCount,
                reservedCount == null ? 0 : Long.parseLong(reservedCount));
    }

    /** 当前可售计数；维度未初始化时为 null。 */
    public Long availableCount(InventoryKey key) {
        String value = redis.opsForValue().get(available(key));
        return value == null ? null : Long.parseLong(value);
    }

    public Outcome reserve(String idempotencyKey, CanonicalReservationRequest request) {
        List<String> keys = new ArrayList<>();
        keys.add(reservation(request.shopId(), idempotencyKey));
        keys.add(expiry(request.shopId()));
        List<String> args = new ArrayList<>();
        args.add(HexFormat.of().formatHex(request.sha256()));
        args.add(encodeLines(request.lines()));
        args.add(Integer.toString(request.ttlSeconds()));
        args.add(idempotencyKey);
        for (CanonicalReservationRequest.Line line : request.lines()) {
            keys.add(available(line.key()));
            keys.add(reserved(line.key()));
            args.add(Integer.toString(line.quantity()));
        }
        return run(RESERVE, keys, args);
    }

    public Outcome claim(long shopId, String idempotencyKey, String paymentReference) {
        return claim(shopId, idempotencyKey, paymentReference, claimOrder);
    }

    /**
     * 旧系统的确认：Redis 中把预留改为 CLAIMED，并在 MySQL 账本中扣减账面库存。两步分属两个系统，
     * 中间的失败（进程崩溃、网络中断）无法回滚另一步——FaultInjector 的 LEGACY_CLAIM_BETWEEN_SYSTEMS 模拟这一刻。
     */
    public Outcome claim(long shopId, String idempotencyKey, String paymentReference, ClaimOrder order) {
        List<String[]> lines = lines(shopId, idempotencyKey);
        if (lines == null) {
            return new Outcome(Outcome.Category.NOT_FOUND, "");
        }
        if (order == ClaimOrder.LEDGER_FIRST) {
            if ("CLAIMED".equals(redis.opsForHash().get(reservation(shopId, idempotencyKey), "status"))) {
                return normalized(claimInRedis(shopId, idempotencyKey, paymentReference, lines));
            }
            adjustLedger(shopId, lines, -1);
            faults.at(FaultInjector.Point.LEGACY_CLAIM_BETWEEN_SYSTEMS);
            Outcome outcome = claimInRedis(shopId, idempotencyKey, paymentReference, lines);
            if (!freshClaim(outcome)) {
                // 补偿：Redis 拒绝了确认（过期、冲突等）或已被别人确认，把刚扣的账本加回去；补偿本身同样可能失败。
                adjustLedger(shopId, lines, +1);
            }
            return normalized(outcome);
        }
        Outcome outcome = claimInRedis(shopId, idempotencyKey, paymentReference, lines);
        if (freshClaim(outcome)) {
            faults.at(FaultInjector.Point.LEGACY_CLAIM_BETWEEN_SYSTEMS);
            adjustLedger(shopId, lines, -1);
        }
        return normalized(outcome);
    }

    /** 本次调用把预留从 ACTIVE 改为 CLAIMED（而不是重复确认）。 */
    private static boolean freshClaim(Outcome outcome) {
        return outcome.category() == Outcome.Category.OK && "CLAIMED".equals(outcome.status());
    }

    /** 重复确认与首次确认对外都是 OK / CLAIMED，与 MySQL 方案的结果类别一致。 */
    private static Outcome normalized(Outcome outcome) {
        return "ALREADY_CLAIMED".equals(outcome.status()) ? new Outcome(Outcome.Category.OK, "CLAIMED") : outcome;
    }

    private Outcome claimInRedis(long shopId, String idempotencyKey, String paymentReference, List<String[]> lines) {
        List<String> keys = new ArrayList<>(List.of(reservation(shopId, idempotencyKey), expiry(shopId),
                paymentReferences(shopId)));
        List<String> args = new ArrayList<>(List.of(paymentReference, idempotencyKey));
        for (String[] line : lines) {
            keys.add(reserved(new InventoryKey(shopId, Long.parseLong(line[0]), Long.parseLong(line[1]))));
            args.add(line[2]);
        }
        // 重复确认返回 OK / ALREADY_CLAIMED，调用方据此避免重复扣减账本。
        return run(CLAIM, keys, args);
    }

    private void adjustLedger(long shopId, List<String[]> lines, int sign) {
        for (String[] line : lines) {
            jdbc.update("UPDATE legacy_inventory_ledger SET on_hand_quantity = on_hand_quantity + ?"
                    + " WHERE shop_id = ? AND inventory_item_id = ? AND location_id = ?",
                    sign * Long.parseLong(line[2]), shopId, Long.parseLong(line[0]), Long.parseLong(line[1]));
        }
    }

    /** 明细在预留后不可变；返回 [商品, 地点, 数量] 列表，预留不存在时为 null。 */
    private List<String[]> lines(long shopId, String idempotencyKey) {
        String encoded = (String) redis.opsForHash().get(reservation(shopId, idempotencyKey), "lines");
        if (encoded == null) {
            return null;
        }
        List<String[]> lines = new ArrayList<>();
        for (String line : encoded.split(",")) {
            lines.add(line.split(":"));
        }
        return lines;
    }

    public Outcome cancel(long shopId, String idempotencyKey) {
        return release(shopId, idempotencyKey, "CANCELLED");
    }

    private Outcome release(long shopId, String idempotencyKey, String target) {
        // 明细在预留后不可变，先读出来以声明脚本要访问的全部键。
        List<String[]> lines = lines(shopId, idempotencyKey);
        if (lines == null) {
            return new Outcome(Outcome.Category.NOT_FOUND, "");
        }
        List<String> keys = new ArrayList<>();
        keys.add(reservation(shopId, idempotencyKey));
        keys.add(expiry(shopId));
        List<String> args = new ArrayList<>();
        args.add(target);
        args.add(idempotencyKey);
        for (String[] parts : lines) {
            InventoryKey key = new InventoryKey(shopId, Long.parseLong(parts[0]), Long.parseLong(parts[1]));
            keys.add(available(key));
            keys.add(reserved(key));
            args.add(parts[2]);
        }
        return run(RELEASE, keys, args);
    }

    /** 旧系统自己的到期清理：每个店铺按 ZSET 中的到期时间取一批，逐个原子释放。 */
    @Scheduled(fixedDelayString = "${stockhold.migration.sweep-interval-millis:1000}")
    public void sweepExpired() {
        Set<String> shops = redis.opsForSet().members(SHOPS);
        if (shops == null) {
            return;
        }
        for (String shop : shops) {
            long shopId = Long.parseLong(shop);
            Set<String> due = redis.opsForZSet().rangeByScore(expiry(shopId), Double.NEGATIVE_INFINITY,
                    System.currentTimeMillis(), 0, sweepBatchSize);
            if (due == null) {
                continue;
            }
            for (String idempotencyKey : due) {
                try {
                    // 应用时钟只用来挑候选；是否到期由脚本内的 Redis 时间判定。
                    release(shopId, idempotencyKey, "EXPIRED");
                } catch (RuntimeException e) {
                    log.warn("Legacy expiry failed for shop {} key {}", shopId, idempotencyKey, e);
                }
            }
        }
    }

    /** 按状态统计店铺内的预留数量；SCAN 遍历，只用于实验规模的一致性检查。 */
    public Map<String, Long> statusCounts(long shopId) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        ScanOptions options = ScanOptions.scanOptions().match("sh:{" + shopId + "}:res:*").count(1000).build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                Object status = redis.opsForHash().get(cursor.next(), "status");
                if (status != null) {
                    counts.merge(status.toString(), 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Outcome run(RedisScript<List> script, List<String> keys, List<String> args) {
        List<Object> result = redis.execute(script, keys, args.toArray());
        return Outcome.of(String.valueOf(result.get(0)), String.valueOf(result.get(1)));
    }

    private static String encodeLines(List<CanonicalReservationRequest.Line> lines) {
        StringBuilder encoded = new StringBuilder();
        for (CanonicalReservationRequest.Line line : lines) {
            if (encoded.length() > 0) {
                encoded.append(',');
            }
            encoded.append(line.key().inventoryItemId()).append(':').append(line.key().locationId()).append(':')
                    .append(line.quantity());
        }
        return encoded.toString();
    }
}
