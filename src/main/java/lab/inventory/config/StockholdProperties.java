package lab.inventory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 实验配置；默认值来自总体技术方案第 10 节，均需压测校准，不是生产推荐值。 */
@ConfigurationProperties("stockhold")
public record StockholdProperties(
        @DefaultValue Reserve reserve,
        @DefaultValue Transaction transaction,
        @DefaultValue Refill refill,
        @DefaultValue Expiry expiry,
        @DefaultValue Admin admin) {

    public record Reserve(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("2000") long budgetMillis,
            @DefaultValue("50") long jitterMinMillis,
            @DefaultValue("150") long jitterMaxMillis,
            @DefaultValue("64") int maxConcurrent,
            @DefaultValue("200") long admissionWaitMillis,
            @DefaultValue("false") boolean unionAllSelect,
            // 池已被别人补满时立即重试一次（文章：补满后等待的预留立即继续）；false 时退避，供对照实验使用。
            @DefaultValue("true") boolean refillImmediateRetry) {
    }

    public record Transaction(
            @DefaultValue("5") int timeoutSeconds,
            @DefaultValue("3") int transientRetries) {
    }

    public record Refill(
            @DefaultValue("100") int insertBatchSize,
            @DefaultValue("true") boolean jobEnabled,
            @DefaultValue("200") int scanPageSize,
            @DefaultValue("2") int workers,
            @DefaultValue("25") int lowWatermarkPercent) {
    }

    public record Expiry(
            @DefaultValue("true") boolean jobEnabled,
            // scan 模式每轮最多处理的候选数。
            @DefaultValue("200") int batchSize,
            // skip-locked：工作线程用 SKIP LOCKED 领取到期预留，多线程 / 多实例自然分摊；scan：逐个扫描候选（原实现）。
            @DefaultValue("skip-locked") String mode,
            @DefaultValue("2") int workers,
            // 每轮最多持续多久；有积压时一轮内持续清理，不再每处理一页就等下一轮。
            @DefaultValue("1000") long drainBudgetMillis,
            // skip-locked 模式下一个事务领取的到期预留数；1 即逐个释放。
            @DefaultValue("50") int claimBatchSize) {
    }

    public record Admin(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token) {
    }
}
