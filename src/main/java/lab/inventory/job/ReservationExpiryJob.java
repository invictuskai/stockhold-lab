package lab.inventory.job;

import java.util.concurrent.TimeUnit;

import lab.inventory.application.ExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定期清理到期预留（默认每 250 ms 一轮；有积压时一轮内持续清理，最多 drain-budget-millis）。
 * 工作线程数固定，不向连接池提交无界任务。停机不会超卖，只会暂时减少可售量。
 */
@Component
@ConditionalOnProperty(name = "stockhold.expiry.job-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ExpiryService expiry;

    public ReservationExpiryJob(ExpiryService expiry) {
        this.expiry = expiry;
    }

    /** 没有积压时不必每 250 ms 都查一次：空闲时退避到这个间隔，避免轮询本身在数据库繁忙时占用连接。 */
    private static final long IDLE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private long idleUntil;

    @Scheduled(fixedDelayString = "${stockhold.expiry.interval-millis:250}")
    public void run() {
        if (System.nanoTime() < idleUntil) {
            return;
        }
        try {
            // 本轮什么都没释放说明当前没有积压：退避一段时间；有积压时保持快速清理。
            idleUntil = expiry.drain() == 0 ? System.nanoTime() + IDLE_INTERVAL_NANOS : 0;
        } catch (RuntimeException e) {
            log.warn("Expiry scan failed; will retry on the next tick: {}", e.toString());
        }
    }
}
