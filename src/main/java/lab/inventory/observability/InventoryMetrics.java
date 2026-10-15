package lab.inventory.observability;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** 业务指标；所有 tag 取值都是固定枚举，避免高基数。 */
@Component
public class InventoryMetrics {

    private final MeterRegistry registry;
    private final AtomicLong expiryLagMillis = new AtomicLong();
    private final AtomicLong expiryBacklog = new AtomicLong();

    public InventoryMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("stockhold.expiry.lag", expiryLagMillis, value -> value.get() / 1000.0)
                .description("Age of the oldest ACTIVE reservation past its expiry")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("stockhold.expiry.backlog", expiryBacklog, AtomicLong::get)
                .description("Expired candidates seen by the last expiry scan")
                .register(registry);
    }

    /** 一次完整业务调用（含重试与事务外等待）的耗时。 */
    public void recordOperation(BusinessOperation operation, String outcome, long nanos) {
        timer("stockhold.operation", operation, "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
    }

    /** 单个数据库事务从开始到提交/回滚的耗时。 */
    public void recordTransaction(BusinessOperation operation, boolean committed, long nanos) {
        timer("stockhold.transaction", operation, "result", committed ? "commit" : "rollback")
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordConnectionAcquire(BusinessOperation operation, long nanos) {
        timer("stockhold.connection.acquire", operation).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordConnectionHold(BusinessOperation operation, long nanos) {
        timer("stockhold.connection.hold", operation).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void recordSql(BusinessOperation operation, long nanos) {
        timer("stockhold.sql", operation).record(nanos, TimeUnit.NANOSECONDS);
    }

    public void reserveRetry(String reason) {
        counter("stockhold.reserve.retries", "reason", reason).increment();
    }

    /** SKIP LOCKED 缺行次数；不代表售罄。 */
    public void poolMiss() {
        counter("stockhold.reserve.pool.miss").increment();
    }

    public void refill(boolean synchronous, int insertedUnits) {
        DistributionSummary.builder("stockhold.refill.units")
                .tag("trigger", synchronous ? "sync" : "background")
                .register(registry)
                .record(insertedUnits);
    }

    public void transientRetry(BusinessOperation operation) {
        counter("stockhold.transaction.retries", "op", operation.tag()).increment();
    }

    public void invariantViolation() {
        counter("stockhold.invariant.violations").increment();
    }

    public void expiryScan(long lagMillis, long candidates) {
        expiryLagMillis.set(lagMillis);
        expiryBacklog.set(candidates);
    }

    private Timer timer(String name, BusinessOperation operation, String... extraTags) {
        return Timer.builder(name).tag("op", operation.tag()).tags(extraTags).register(registry);
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(registry);
    }
}
