package lab.inventory.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

/**
 * 迁移实验测试用的 Redis：优先使用 STOCKHOLD_TEST_REDIS_HOST / PORT 指向的实例
 * （例如 compose 的 migration profile），否则在 Docker 可用时启动 Testcontainers Redis 7.4，都没有则跳过。
 */
public final class RedisTestServer implements ExecutionCondition {

    public record Address(String host, int port) {
    }

    private static Address address;
    private static String unavailableReason;

    public static synchronized Address resolve() {
        if (address != null || unavailableReason != null) {
            return address;
        }
        String port = System.getenv("STOCKHOLD_TEST_REDIS_PORT");
        if (port != null && !port.isBlank()) {
            address = new Address(System.getenv().getOrDefault("STOCKHOLD_TEST_REDIS_HOST", "127.0.0.1"),
                    Integer.parseInt(port));
            return address;
        }
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        if (!docker) {
            unavailableReason = "No Redis available: set STOCKHOLD_TEST_REDIS_PORT or start Docker";
            return null;
        }
        @SuppressWarnings("resource") // 单例容器随 JVM 退出由 Ryuk 回收
        GenericContainer<?> container = new GenericContainer<>("redis:7.4").withExposedPorts(6379);
        container.start();
        address = new Address(container.getHost(), container.getMappedPort(6379));
        return address;
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return resolve() != null
                ? ConditionEvaluationResult.enabled("Redis is available")
                : ConditionEvaluationResult.disabled(unavailableReason);
    }
}
