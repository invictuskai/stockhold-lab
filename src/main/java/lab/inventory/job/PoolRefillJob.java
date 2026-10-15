package lab.inventory.job;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.annotation.PreDestroy;
import lab.inventory.application.PoolRefillService;
import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.InventoryKey;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.persistence.InventoryLedgerRepository.RefillCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台补充：keyset 分页扫描库存维度，把池低于水位且 F > 0 的维度填充到容量。
 * 这是实验方案，维度数量巨大时不能直接扩展为每秒全表扫描。
 * 正确性不依赖本任务及时执行：reserve 缺行时有同步补充路径。
 */
@Component
@ConditionalOnProperty(name = "stockhold.refill.job-enabled", havingValue = "true", matchIfMissing = true)
public class PoolRefillJob {

    private static final Logger log = LoggerFactory.getLogger(PoolRefillJob.class);

    private final InventoryLedgerRepository ledgers;
    private final PoolRefillService refills;
    private final StockholdProperties.Refill config;
    private final ExecutorService workers;

    public PoolRefillJob(InventoryLedgerRepository ledgers, PoolRefillService refills,
            StockholdProperties properties) {
        this.ledgers = ledgers;
        this.refills = refills;
        this.config = properties.refill();
        this.workers = Executors.newFixedThreadPool(Math.max(1, config.workers()), runnable -> {
            Thread thread = new Thread(runnable, "pool-refill-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Scheduled(fixedDelayString = "${stockhold.refill.interval-millis:1000}")
    public void run() {
        try {
            // 多实例允许重复探测，随机抖动只为错开扫描；实际补充由账本锁串行化。
            Thread.sleep(ThreadLocalRandom.current().nextLong(0, 100));
            scan();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Pool refill scan failed; will retry on the next tick: {}", e.toString());
        }
    }

    /** 扫描一遍所有候选维度；页内补充完成后才返回。 */
    public void scan() throws InterruptedException {
        InventoryKey cursor = null;
        while (true) {
            List<RefillCandidate> page;
            try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.REFILL)) {
                page = ledgers.scanRefillCandidates(cursor, config.scanPageSize());
            }
            if (page.isEmpty()) {
                return;
            }
            List<Callable<Void>> tasks = new ArrayList<>();
            for (RefillCandidate candidate : page) {
                if (candidate.poolCount() * 100 < (long) candidate.poolCapacity() * config.lowWatermarkPercent()) {
                    tasks.add(() -> refillQuietly(candidate.key()));
                }
            }
            // 等这一页补充完成后再取下一页，待处理任务数始终有界。
            workers.invokeAll(tasks);
            cursor = page.get(page.size() - 1).key();
            if (page.size() < config.scanPageSize()) {
                return;
            }
        }
    }

    private Void refillQuietly(InventoryKey key) {
        try {
            refills.refillOneDimension(key, false);
        } catch (RuntimeException e) {
            log.warn("Background refill failed for one dimension in shop {}: {}", key.shopId(), e.toString());
        }
        return null;
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }
}
