package lab.inventory.application;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.SqlErrors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 所有库存写事务的唯一入口：显式 READ COMMITTED、带业务标签、测量事务时长。
 * 一次 execute 就是一个完整短事务；回调抛出任何异常都会回滚整个事务。
 */
@Component
public class TransactionRunner {

    private final TransactionTemplate template;
    private final InventoryMetrics metrics;
    private final StockholdProperties properties;

    public TransactionRunner(PlatformTransactionManager transactionManager, InventoryMetrics metrics,
            StockholdProperties properties) {
        this.template = new TransactionTemplate(transactionManager);
        this.template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.template.setTimeout(properties.transaction().timeoutSeconds());
        this.metrics = metrics;
        this.properties = properties;
    }

    public <T> T execute(BusinessOperation operation, Supplier<T> body) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 禁止在未结束的事务里再开事务：外层会占着锁和连接等待内层。
            throw new IllegalStateException("Inventory transactions must not be nested");
        }
        long start = System.nanoTime();
        boolean committed = false;
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(operation)) {
            T result = template.execute(status -> body.get());
            committed = true;
            return result;
        } finally {
            metrics.recordTransaction(operation, committed, System.nanoTime() - start);
        }
    }

    /** 死锁 / 锁等待超时：整个事务回滚后，在事务外抖动并有界重试。 */
    public <T> T executeWithRetry(BusinessOperation operation, Supplier<T> body) {
        int maxAttempts = Math.max(1, properties.transaction().transientRetries());
        for (int attempt = 1;; attempt++) {
            try {
                return execute(operation, body);
            } catch (RuntimeException e) {
                if (!SqlErrors.isTransientLockFailure(e)) {
                    throw e;
                }
                if (attempt >= maxAttempts) {
                    throw ApiException.busy("Inventory is temporarily contended; retry the request.");
                }
                metrics.transientRetry(operation);
                sleepJitter(Long.MAX_VALUE);
            }
        }
    }

    /** 事务外的抖动等待；不得在持有数据库连接时调用。 */
    public void sleepJitter(long maxMillis) {
        long min = properties.reserve().jitterMinMillis();
        long max = Math.max(min, properties.reserve().jitterMaxMillis());
        long millis = Math.min(maxMillis, ThreadLocalRandom.current().nextLong(min, max + 1));
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.busy("Interrupted while backing off.");
        }
    }
}
