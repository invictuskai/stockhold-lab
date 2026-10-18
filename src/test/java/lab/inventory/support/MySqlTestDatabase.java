package lab.inventory.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * 锁语义测试只在真实 MySQL 上运行，不用内存数据库替代。
 * 优先使用环境变量指向的可重置隔离实例；否则在 Docker 可用时启动 Testcontainers MySQL 8.4；
 * 两者都没有则跳过（而不是假装通过）。
 */
public final class MySqlTestDatabase implements ExecutionCondition {

    public static final String URL_PARAMS = "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
            + "&sessionVariables=transaction_isolation='READ-COMMITTED',innodb_lock_wait_timeout=1"
            + "&characterEncoding=UTF-8";

    public record Coordinates(String url, String username, String password) {
    }

    private static Coordinates coordinates;
    private static Coordinates observer;
    private static String unavailableReason;

    /**
     * 只读观察 performance_schema（data_locks 等）的独立账号；业务账号不应拥有该权限。
     * 环境变量路径需显式设置 STOCKHOLD_TEST_OBSERVER_USER / PASSWORD；Testcontainers 路径使用容器 root。
     */
    public static synchronized Optional<Coordinates> observer() {
        return resolve() == null ? Optional.empty() : Optional.ofNullable(observer);
    }

    public static synchronized Coordinates resolve() {
        if (coordinates != null || unavailableReason != null) {
            return coordinates;
        }
        String url = System.getenv("STOCKHOLD_TEST_JDBC_URL");
        if (url != null && !url.isBlank()) {
            coordinates = new Coordinates(url.contains("?") ? url : url + "?" + URL_PARAMS,
                    System.getenv().getOrDefault("STOCKHOLD_TEST_DB_USER", "root"),
                    System.getenv().getOrDefault("STOCKHOLD_TEST_DB_PASSWORD", ""));
            String observerUser = System.getenv("STOCKHOLD_TEST_OBSERVER_USER");
            if (observerUser != null && !observerUser.isBlank()) {
                observer = new Coordinates(observerUrl(coordinates.url()), observerUser,
                        System.getenv().getOrDefault("STOCKHOLD_TEST_OBSERVER_PASSWORD", ""));
            }
            resetIsolatedDatabase(coordinates);
            return coordinates;
        }
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        if (!docker) {
            unavailableReason = "No real MySQL available: set STOCKHOLD_TEST_JDBC_URL or start Docker";
            return null;
        }
        @SuppressWarnings("resource") // 单例容器随 JVM 退出由 Ryuk 回收
        MySQLContainer<?> container = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("stockhold_test")
                .withCommand("--default-time-zone=+00:00", "--max-connections=300");
        container.start();
        coordinates = new Coordinates(container.getJdbcUrl() + "?" + URL_PARAMS, container.getUsername(),
                container.getPassword());
        observer = new Coordinates(observerUrl(coordinates.url()), "root", container.getPassword());
        return coordinates;
    }

    /**
     * 环境变量指向的是“可随时重置的隔离库”：每次测试 JVM 启动时清空一次，避免历次运行的残留
     * （例如早已过期的 ACTIVE 预留）拖慢全局扫描类测试。为防误指向业务库，只处理库名以 _test 结尾的库；
     * Flyway 的历史表保留，表结构不变。
     */
    private static void resetIsolatedDatabase(Coordinates db) {
        try (Connection connection = DriverManager.getConnection(db.url(), db.username(), db.password());
                Statement statement = connection.createStatement()) {
            String schema = connection.getCatalog();
            if (schema == null || !schema.endsWith("_test")) {
                throw new IllegalStateException("Refusing to reset database '" + schema
                        + "': STOCKHOLD_TEST_JDBC_URL must point at an isolated *_test database");
            }
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery("SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'"
                    + " AND table_name <> 'flyway_schema_history'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE `" + table + "`");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the isolated test database", e);
        }
    }

    /** 观察账号不需要业务库权限：默认库换成 performance_schema。 */
    private static String observerUrl(String url) {
        return url.replaceFirst("^(jdbc:mysql://[^/]+/)[^?]*", "$1performance_schema");
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return resolve() != null
                ? ConditionEvaluationResult.enabled("Real MySQL is available")
                : ConditionEvaluationResult.disabled(unavailableReason);
    }
}
