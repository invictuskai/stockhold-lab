package lab.inventory.job;

import lab.inventory.application.ExpiryService;
import lab.inventory.config.StockholdProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每秒处理一批到期候选，在调度线程上逐个串行释放，不向连接池提交无界任务。
 * 停机不会超卖，只会暂时减少可售量。
 */
@Component
@ConditionalOnProperty(name = "stockhold.expiry.job-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ExpiryService expiry;
    private final int batchSize;

    public ReservationExpiryJob(ExpiryService expiry, StockholdProperties properties) {
        this.expiry = expiry;
        this.batchSize = properties.expiry().batchSize();
    }

    @Scheduled(fixedDelayString = "${stockhold.expiry.interval-millis:1000}")
    public void run() {
        try {
            expiry.sweep(batchSize);
        } catch (RuntimeException e) {
            log.warn("Expiry scan failed; will retry on the next tick: {}", e.toString());
        }
    }
}
