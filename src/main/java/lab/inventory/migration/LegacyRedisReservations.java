package lab.inventory.migration;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 被替换的旧系统：每个维度一个可售计数（预留 DECRBY、释放 INCRBY），预留记录与 MySQL 账本不在同一事务中。
 * 每个操作是一个 Lua 脚本，保证单个 Redis 内的多维度原子性——这是旧方案能做到的最好情况。
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

    private final StringRedisTemplate redis;
    private final int sweepBatchSize;

    public LegacyRedisReservations(StringRedisTemplate redis,
            @Value("${stockhold.migration.sweep-batch-size:200}") int sweepBatchSize) {
        this.redis = redis;
        this.sweepBatchSize = sweepBatchSize;
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

    /** 初始化（或重置）一个维度的可售计数；对应旧系统中把账面库存同步进 Redis。 */
    public void seed(InventoryKey key, long available) {
        redis.opsForValue().set(available(key), Long.toString(available));
        redis.opsForSet().add(SHOPS, Long.toString(key.shopId()));
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
            args.add(Integer.toString(line.quantity()));
        }
        return run(RESERVE, keys, args);
    }

    public Outcome claim(long shopId, String idempotencyKey, String paymentReference) {
        return run(CLAIM, List.of(reservation(shopId, idempotencyKey), expiry(shopId), paymentReferences(shopId)),
                List.of(paymentReference, idempotencyKey));
    }

    public Outcome cancel(long shopId, String idempotencyKey) {
        return release(shopId, idempotencyKey, "CANCELLED");
    }

    private Outcome release(long shopId, String idempotencyKey, String target) {
        // 明细在预留后不可变，先读出来以声明脚本要访问的全部键。
        String lines = (String) redis.opsForHash().get(reservation(shopId, idempotencyKey), "lines");
        if (lines == null) {
            return new Outcome(Outcome.Category.NOT_FOUND, "");
        }
        List<String> keys = new ArrayList<>();
        keys.add(reservation(shopId, idempotencyKey));
        keys.add(expiry(shopId));
        List<String> args = new ArrayList<>();
        args.add(target);
        args.add(idempotencyKey);
        for (String line : lines.split(",")) {
            String[] parts = line.split(":");
            keys.add(available(new InventoryKey(shopId, Long.parseLong(parts[0]), Long.parseLong(parts[1]))));
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
