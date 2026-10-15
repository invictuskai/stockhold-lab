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
            @DefaultValue("false") boolean unionAllSelect) {
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
            @DefaultValue("200") int batchSize) {
    }

    public record Admin(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token) {
    }
}
