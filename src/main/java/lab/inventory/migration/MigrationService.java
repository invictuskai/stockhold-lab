package lab.inventory.migration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.MeterRegistry;
import lab.inventory.application.InventoryService;
import lab.inventory.application.ReservationFacade;
import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 迁移路由：按店铺的 {@link MigrationMode} 决定事实来源，并在影子阶段用同一请求执行另一个系统、比对结果类别。
 *
 * <p>影子只在主系统成功之后执行：影子系统不能产生主系统拒绝了的状态。主系统成功而影子失败时，两个系统从此出现分歧，
 * 计入不一致并保留样本；这类分歧在切换前必须为零或被修复，否则切换后会有预留在新系统中不存在。
 */
@Service
@Profile("migration")
public class MigrationService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String MODES = "sh:migration:mode";

    public record Mismatch(Instant at, String operation, long shopId, String idempotencyKey, MigrationMode mode,
            Outcome primary, Outcome shadow) {
    }

    /** 主系统的结果；MySQL 为主时附带其预留 ID。 */
    public record Result(MigrationMode mode, String primarySystem, Outcome outcome, UUID mysqlReservationId) {
    }

    public record DimensionDiff(long inventoryItemId, long locationId, Long redisAvailable, Long mysqlAvailable,
            boolean equal) {
    }

    public record Consistency(long shopId, boolean consistent, List<DimensionDiff> dimensions,
            Map<String, Long> redisStatusCounts, Map<String, Long> mysqlStatusCounts) {
    }

    private final LegacyRedisReservations redis;
    private final ReservationFacade mysql;
    private final InventoryService inventory;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final int sampleSize;
    private final Deque<Mismatch> samples = new ConcurrentLinkedDeque<>();
    private final AtomicInteger sampleCount = new AtomicInteger();

    public MigrationService(LegacyRedisReservations redis, ReservationFacade mysql, InventoryService inventory,
            StringRedisTemplate redisTemplate, JdbcTemplate jdbc, MeterRegistry meters,
            @Value("${stockhold.migration.mismatch-sample-size:500}") int sampleSize) {
        this.redis = redis;
        this.mysql = mysql;
        this.inventory = inventory;
        this.redisTemplate = redisTemplate;
        this.jdbc = jdbc;
        this.meters = meters;
        this.sampleSize = sampleSize;
    }

    // ------------------------------------------------------------------ 模式（所有实例共享）

    public MigrationMode mode(long shopId) {
        Object value = redisTemplate.opsForHash().get(MODES, Long.toString(shopId));
        return value == null ? MigrationMode.REDIS_ONLY : MigrationMode.valueOf(value.toString());
    }

    public void setMode(long shopId, MigrationMode mode) {
        redisTemplate.opsForHash().put(MODES, Long.toString(shopId), mode.name());
    }

    // ------------------------------------------------------------------ 业务操作

    public Result reserve(long shopId, String idempotencyKey, Integer ttlSeconds,
            List<CanonicalReservationRequest.RawLine> rawLines) {
        requireToken(idempotencyKey, "Idempotency-Key");
        // 参数错误两个系统都会拒绝，直接返回，不进入比对。
        CanonicalReservationRequest request = CanonicalReservationRequest.of(shopId, ttlSeconds, rawLines);
        UUID[] mysqlId = new UUID[1];
        Supplier<Outcome> onMysql = () -> {
            ReserveOutcome outcome = mysql.reserve(shopId, idempotencyKey, ttlSeconds, rawLines);
            mysqlId[0] = outcome.reservation().header().reservationId();
            return new Outcome(outcome.replayed() ? Outcome.Category.REPLAYED : Outcome.Category.CREATED,
                    outcome.reservation().header().status().name());
        };
        return route("reserve", shopId, idempotencyKey, () -> redis.reserve(idempotencyKey, request), onMysql,
                false, mysqlId);
    }

    public Result claim(long shopId, String idempotencyKey, String paymentReference) {
        requireToken(idempotencyKey, "key");
        requireToken(paymentReference, "paymentReference");
        UUID[] mysqlId = new UUID[1];
        Supplier<Outcome> onMysql = () -> {
            Reservation reservation = mysql.getByIdempotencyKey(shopId, idempotencyKey);
            mysqlId[0] = reservation.header().reservationId();
            return new Outcome(Outcome.Category.OK,
                    mysql.claim(shopId, mysqlId[0], paymentReference).header().status().name());
        };
        return route("claim", shopId, idempotencyKey, () -> redis.claim(shopId, idempotencyKey, paymentReference),
                onMysql, true, mysqlId);
    }

    public Result cancel(long shopId, String idempotencyKey) {
        requireToken(idempotencyKey, "key");
        UUID[] mysqlId = new UUID[1];
        Supplier<Outcome> onMysql = () -> {
            Reservation reservation = mysql.getByIdempotencyKey(shopId, idempotencyKey);
            mysqlId[0] = reservation.header().reservationId();
            return new Outcome(Outcome.Category.OK, mysql.cancel(shopId, mysqlId[0]).header().status().name());
        };
        return route("cancel", shopId, idempotencyKey, () -> redis.cancel(shopId, idempotencyKey), onMysql, true,
                mysqlId);
    }

    private Result route(String operation, long shopId, String idempotencyKey, Supplier<Outcome> onRedis,
            Supplier<Outcome> onMysql, boolean reservationLevel, UUID[] mysqlId) {
        MigrationMode mode = mode(shopId);
        Supplier<Outcome> primary = mode.mysqlPrimary() ? onMysql : onRedis;
        Supplier<Outcome> shadow = mode.mysqlPrimary() ? onRedis : onMysql;
        String primarySystem = mode.mysqlPrimary() ? "mysql" : "redis";

        Outcome primaryOutcome = attempt(primary);
        meters.counter("stockhold.migration.primary", "op", operation, "system", primarySystem,
                "outcome", primaryOutcome.category().name()).increment();
        if (mode.shadowed()) {
            String result;
            if (!primaryOutcome.category().succeeded()) {
                result = "skipped";
            } else {
                Outcome shadowOutcome = attempt(shadow);
                // 两个系统的重复确认 / 取消都报 OK，预留重放都报 REPLAYED，因此可以直接比较类别。
                if (primaryOutcome.category() == shadowOutcome.category()) {
                    result = "match";
                } else {
                    result = "mismatch";
                    recordMismatch(new Mismatch(Instant.now(), operation, shopId, idempotencyKey, mode,
                            primaryOutcome, shadowOutcome));
                }
            }
            meters.counter("stockhold.migration.shadow", "op", operation, "result", result).increment();
        }
        if (!primaryOutcome.category().succeeded()) {
            throw primaryOutcome.toException(reservationLevel);
        }
        return new Result(mode, primarySystem, primaryOutcome, mode.mysqlPrimary() ? mysqlId[0] : null);
    }

    /** 主 / 影子调用的异常都转换为结果类别；影子的失败绝不影响响应。 */
    private static Outcome attempt(Supplier<Outcome> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            return Outcome.failure(e);
        }
    }

    private void recordMismatch(Mismatch mismatch) {
        samples.addLast(mismatch);
        if (sampleCount.incrementAndGet() > sampleSize) {
            samples.pollFirst();
            sampleCount.decrementAndGet();
        }
    }

    public List<Mismatch> mismatches() {
        return new ArrayList<>(samples);
    }

    // ------------------------------------------------------------------ 初始化与一致性检查

    public void seedLegacy(InventoryKey key, long available) {
        redis.seed(key, available);
    }

    /**
     * 两个系统对同一批维度的可售量是否一致：Redis 的可售计数 = H0 - 已确认 - ACTIVE 预留，
     * 与 MySQL 的 effectiveAvailable = H - R 应相等。只在负载静止、双方到期清理都追上后才有意义。
     */
    public Consistency consistency(long shopId, List<InventoryKey> keys) {
        List<DimensionDiff> dimensions = new ArrayList<>();
        boolean consistent = true;
        for (InventoryKey key : keys) {
            Long redisAvailable = redis.availableCount(key);
            Long mysqlAvailable;
            try {
                InventorySnapshot snapshot = inventory.snapshot(key);
                mysqlAvailable = snapshot.effectiveAvailable();
            } catch (ApiException e) {
                mysqlAvailable = null;
            }
            boolean equal = redisAvailable != null && redisAvailable.equals(mysqlAvailable);
            consistent &= equal;
            dimensions.add(new DimensionDiff(key.inventoryItemId(), key.locationId(), redisAvailable, mysqlAvailable,
                    equal));
        }
        Map<String, Long> mysqlCounts = new TreeMap<>();
        jdbc.query("SELECT status, COUNT(*) FROM reservations WHERE shop_id = ? GROUP BY status",
                rs -> {
                    mysqlCounts.put(rs.getString(1), rs.getLong(2));
                }, shopId);
        Map<String, Long> redisCounts = redis.statusCounts(shopId);
        consistent &= redisCounts.equals(mysqlCounts);
        return new Consistency(shopId, consistent, dimensions, redisCounts, mysqlCounts);
    }

    /** 旧系统内部的账实差异（Redis 可售 + 已预留 − 旧账本账面），见 {@link LegacyRedisReservations#claim}。 */
    public List<LegacyRedisReservations.LegacyDrift> legacyDrift(List<InventoryKey> keys) {
        return keys.stream().map(redis::drift).toList();
    }

    private static void requireToken(String value, String name) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw ApiException.invalidArgument(name + " must be 1..128 characters of [A-Za-z0-9._:-].");
        }
    }
}
