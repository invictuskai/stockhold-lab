package lab.inventory.application;

import java.util.Optional;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 把一部分读流量移到只读副本（文章：“Some read load was moved to replicas”）。
 *
 * <p>只服务客户端显式声明可以接受陈旧数据（consistency=eventual）的公开查询：库存快照与预留查询。
 * 预留、确认、取消、补充、到期、审计等任何依赖最新状态的路径始终读主库。副本可能落后于主库，
 * 例如刚创建的预留在副本上暂时查不到——这是调用方选择 eventual 时接受的语义。
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
    private final InventoryService inventory;
    private final ReservationFacade reservations;

    public ReplicaReads(@Value("${stockhold.replica.url:}") String url,
            @Value("${stockhold.replica.username:${spring.datasource.username:}}") String username,
            @Value("${stockhold.replica.password:${spring.datasource.password:}}") String password,
            @Value("${stockhold.replica.pool-size:8}") int poolSize,
            InventoryService inventory, ReservationFacade reservations) {
        this.inventory = inventory;
        this.reservations = reservations;
        if (url == null || url.isBlank()) {
            this.replica = null;
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
        this.replica = new HikariDataSource(config);
        JdbcTemplate jdbc = new JdbcTemplate(replica);
        this.replicaSnapshots = new InventorySnapshotRepository(jdbc);
        this.replicaReservations = new ReservationRepository(jdbc);
    }

    public boolean configured() {
        return replica != null;
    }

    private boolean useReplica(Consistency consistency) {
        return consistency == Consistency.EVENTUAL && replica != null;
    }

    public Read<InventorySnapshot> snapshot(InventoryKey key, Consistency consistency) {
        if (!useReplica(consistency)) {
            return new Read<>(inventory.snapshot(key), "primary");
        }
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.QUERY)) {
            return new Read<>(replicaSnapshots.snapshot(key).orElseThrow(() -> ApiException.inventoryNotFound(key)),
                    "replica");
        }
    }

    public Read<Reservation> reservation(long shopId, UUID reservationId, Consistency consistency) {
        if (!useReplica(consistency)) {
            return new Read<>(reservations.get(shopId, reservationId), "primary");
        }
        return new Read<>(onReplica(replicaReservations.find(shopId, reservationId)), "replica");
    }

    public Read<Reservation> reservationByKey(long shopId, String idempotencyKey, Consistency consistency) {
        if (!useReplica(consistency)) {
            return new Read<>(reservations.getByIdempotencyKey(shopId, idempotencyKey), "primary");
        }
        ReservationFacade.requireToken(idempotencyKey, "key");
        return new Read<>(onReplica(replicaReservations.findByIdempotencyKey(shopId, idempotencyKey)), "replica");
    }

    private static Reservation onReplica(Optional<Reservation> found) {
        return found.orElseThrow(ApiException::reservationNotFound);
    }

    @Override
    public void destroy() {
        if (replica != null) {
            replica.close();
        }
    }
}
