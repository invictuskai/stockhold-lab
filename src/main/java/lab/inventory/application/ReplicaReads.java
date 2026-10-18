package lab.inventory.application;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.persistence.InventorySnapshotRepository;
import lab.inventory.persistence.ReservationRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把一部分读流量移到只读副本（文章：“Some read load was moved to replicas”）。
 *
 * <p>只服务客户端显式声明可以接受陈旧数据（consistency=eventual）的公开查询：库存快照与预留查询。
 * 预留、确认、取消、补充、到期、审计等任何依赖最新状态的路径始终读主库。
 *
 * <p>延迟保护：主库定期写心跳（replica_heartbeat），副本上心跳的年龄超过 max-staleness-millis、
 * 副本不可达、或副本上查不到时，eventual 读回落主库（X-Read-Source: primary-fallback）。
 * 因此副本最多提供落后不超过阈值的数据，且不会把复制延迟报成 404。依赖主库与副本时钟同步。
 *
 * <p>副本连接池由本组件自己持有，不注册为 DataSource Bean，以免影响主数据源的自动配置。未配置副本时一律读主库。
 */
@Component
public class ReplicaReads implements DisposableBean {

    public enum Consistency {
        STRONG, EVENTUAL;

        public static Consistency parse(String value) {
            if (value == null || value.isBlank() || value.equalsIgnoreCase("strong")) {
                return STRONG;
            }
            if (value.equalsIgnoreCase("eventual")) {
                return EVENTUAL;
            }
            throw ApiException.invalidArgument("consistency must be strong or eventual.");
        }
    }

    /** 查询结果及其来源；来源写入响应头 X-Read-Source。 */
    public record Read<T>(T value, String source) {
    }

    private final HikariDataSource replica;
    private final InventorySnapshotRepository replicaSnapshots;
    private final ReservationRepository replicaReservations;
    private final JdbcTemplate replicaJdbc;
    private final JdbcTemplate primaryJdbc;
    private final InventoryService inventory;
    private final ReservationFacade reservations;
    private final MeterRegistry meters;
    private final boolean heartbeatEnabled;
    private final long maxStalenessMillis;
    private volatile long stalenessCheckedAt;
    private volatile boolean freshEnough;

    public ReplicaReads(@Value("${stockhold.replica.url:}") String url,
            @Value("${stockhold.replica.username:${spring.datasource.username:}}") String username,
            @Value("${stockhold.replica.password:${spring.datasource.password:}}") String password,
            @Value("${stockhold.replica.pool-size:8}") int poolSize,
            @Value("${stockhold.replica.heartbeat-enabled:true}") boolean heartbeatEnabled,
            @Value("${stockhold.replica.max-staleness-millis:1500}") long maxStalenessMillis,
            InventoryService inventory, ReservationFacade reservations, JdbcTemplate primaryJdbc,
            MeterRegistry meters) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.primaryJdbc = primaryJdbc;
        this.meters = meters;
        this.heartbeatEnabled = heartbeatEnabled;
        this.maxStalenessMillis = maxStalenessMillis;
        if (url == null || url.isBlank()) {
            this.replica = null;
            this.replicaJdbc = null;
            this.replicaSnapshots = null;
            this.replicaReservations = null;
            return;
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("stockhold-replica");
        config.setReadOnly(true);
        config.setConnectionTimeout(2000);
        // 副本可能暂时不可用：启动时不连接，读时失败则回落主库。
        config.setInitializationFailTimeout(-1);
        this.replica = new HikariDataSource(config);
        this.replicaJdbc = new JdbcTemplate(replica);
        this.replicaSnapshots = new InventorySnapshotRepository(replicaJdbc);
        this.replicaReservations = new ReservationRepository(replicaJdbc);
    }

    public boolean configured() {
        return replica != null;
    }

    /** 主库心跳：配置了副本时定期写入，经复制到达副本后用于判断副本落后程度。 */
    @Scheduled(fixedDelayString = "${stockhold.replica.heartbeat-millis:500}")
    public void heartbeat() {
        if (replica != null && heartbeatEnabled) {
            // 写入即自愈：心跳行缺失（例如被误删或测试库被清空）时重新插入，而不是悄悄写不进去。
            primaryJdbc.update("INSERT INTO replica_heartbeat (id, beat_at) VALUES (1, UTC_TIMESTAMP(6))"
                    + " ON DUPLICATE KEY UPDATE beat_at = VALUES(beat_at)");
        }
    }

    /**
     * 副本上最新心跳的年龄（毫秒），即副本落后程度的上界；副本不可达时为 null。
     * 结果缓存约 200 ms，避免每次查询都多一次往返。
     */
    public Long stalenessMillis() {
        try {
            return replicaJdbc.queryForObject("SELECT TIMESTAMPDIFF(MICROSECOND, beat_at, UTC_TIMESTAMP(6)) DIV 1000"
                    + " FROM replica_heartbeat WHERE id = 1", Long.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean replicaFreshEnough() {
        long now = System.nanoTime();
        if (now - stalenessCheckedAt > 200_000_000L) {
            Long staleness = stalenessMillis();
            freshEnough = staleness != null && staleness <= maxStalenessMillis;
            stalenessCheckedAt = now;
        }
        return freshEnough;
    }

    /**
     * eventual 读的路由：副本足够新则读副本；副本落后超过阈值、不可达或查不到（可能尚未复制）时回落主库，
     * 来源标为 primary-fallback。strong 读一律主库。
     */
    private <T> Read<T> route(Consistency consistency, Supplier<Optional<T>> onReplica, Supplier<T> onPrimary) {
        if (consistency != Consistency.EVENTUAL || replica == null) {
            return new Read<>(onPrimary.get(), "primary");
        }
        if (!replicaFreshEnough()) {
            return fallback("lagging", onPrimary);
        }
        Optional<T> found;
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.QUERY)) {
            found = onReplica.get();
        } catch (DataAccessException e) {
            return fallback("unavailable", onPrimary);
        }
        if (found.isEmpty()) {
            // 副本上没有：可能是刚写入、尚未复制到副本。回查主库，避免把复制延迟报成 404。
            return fallback("missing", onPrimary);
        }
        // 同一个指标名的标签键必须一致（Prometheus 要求），否则后注册的那组会被丢弃。
        meters.counter("stockhold.replica.reads", "source", "replica", "reason", "fresh").increment();
        return new Read<>(found.get(), "replica");
    }

    private <T> Read<T> fallback(String reason, Supplier<T> onPrimary) {
        meters.counter("stockhold.replica.reads", "source", "primary_fallback", "reason", reason).increment();
        return new Read<>(onPrimary.get(), "primary-fallback");
    }

    public Read<InventorySnapshot> snapshot(InventoryKey key, Consistency consistency) {
        return route(consistency, () -> replicaSnapshots.snapshot(key), () -> inventory.snapshot(key));
    }

    public Read<Reservation> reservation(long shopId, UUID reservationId, Consistency consistency) {
        return route(consistency, () -> replicaReservations.find(shopId, reservationId),
                () -> reservations.get(shopId, reservationId));
    }

    public Read<Reservation> reservationByKey(long shopId, String idempotencyKey, Consistency consistency) {
        ReservationFacade.requireToken(idempotencyKey, "key");
        return route(consistency, () -> replicaReservations.findByIdempotencyKey(shopId, idempotencyKey),
                () -> reservations.getByIdempotencyKey(shopId, idempotencyKey));
    }

    @Override
    public void destroy() {
        if (replica != null) {
            replica.close();
        }
    }
}
