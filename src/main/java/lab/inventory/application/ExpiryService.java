package lab.inventory.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lab.inventory.config.StockholdProperties;

import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.ReservationRepository;
import lab.inventory.persistence.ReservationRepository.ExpiryCandidate;
import lab.inventory.persistence.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

/**
 * 到期释放（7.8）。默认 skip-locked 模式：若干工作线程各自循环“用 SKIP LOCKED 领取一个到期预留并释放”，
 * 每个事务只持一个头锁；多个线程、多个实例自然分摊积压，不会排队等同一个头。scan 模式为原实现：
 * 非锁定扫描候选，逐个用普通头锁释放（多实例会扫到相同候选）。
 */
@Service
public class ExpiryService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);
    private static final int PAGE_SIZE = 100;

    private static final int BACKLOG_COUNT_CAP = 100_000;

    private final ReservationTransactionService transactions;
    private final ReservationRepository reservations;
    private final InventoryMetrics metrics;
    private final StockholdProperties.Expiry config;
    private final ExecutorService workers;

    public ExpiryService(ReservationTransactionService transactions, ReservationRepository reservations,
            InventoryMetrics metrics, StockholdProperties properties) {
        this.transactions = transactions;
        this.reservations = reservations;
        this.metrics = metrics;
        this.config = properties.expiry();
        int threads = Math.max(1, config.workers());
        AtomicInteger sequence = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "expiry-worker-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 后台任务的一轮：按配置的模式清理积压，并更新积压指标。返回本轮释放的数量。 */
    public int drain() {
        int released = "scan".equalsIgnoreCase(config.mode()) ? sweep(config.batchSize()) : drainSkipLocked();
        recordBacklog();
        return released;
    }

    private int drainSkipLocked() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.drainBudgetMillis());
        AtomicInteger released = new AtomicInteger();
        List<Future<?>> running = new ArrayList<>();
        for (int i = 0; i < Math.max(1, config.workers()); i++) {
            running.add(workers.submit(() -> {
                while (System.nanoTime() < deadline) {
                    int result = expireNextDue();
                    if (result < 0) {
                        return; // 没有可领取的到期预留
                    }
                    released.addAndGet(result);
                }
            }));
        }
        for (Future<?> future : running) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("Expiry worker failed; will retry on the next tick: {}", e.getCause().toString());
            }
        }
        return released.get();
    }

    /** 正数 = 本批释放的数量，0 = 本次竞争失败（下一次再试），-1 = 没有可领取的到期预留。 */
    private int expireNextDue() {
        long start = System.nanoTime();
        String outcome = "error";
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.EXPIRY)) {
            int released = transactions.expireDueBatch(Math.max(1, config.claimBatchSize()));
            outcome = released > 0 ? "released" : "idle";
            metrics.expiryReleased(released);
            return released > 0 ? released : -1;
        } catch (RuntimeException e) {
            if (SqlErrors.isTransientLockFailure(e)) {
                outcome = "contended";
                return 0;
            }
            log.error("Expiry failed", e);
            return -1;
        } finally {
            if (!outcome.equals("idle")) {
                metrics.recordOperation(BusinessOperation.EXPIRY, outcome, System.nanoTime() - start);
            }
        }
    }

    /** 最老未释放到期时间与积压数量：监控释放是否跟得上，而不只是任务有没有报错。 */
    private void recordBacklog() {
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.EXPIRY)) {
            long lag = reservations.oldestOverdue()
                    .map(oldest -> Math.max(0, Duration.between(oldest, Instant.now()).toMillis())).orElse(0L);
            metrics.expiryScan(lag, reservations.overdueCount(BACKLOG_COUNT_CAP));
        }
    }

    @Override
    public void destroy() {
        workers.shutdownNow();
    }

    /**
     * 处理最多 maxCandidates 个到期候选。多实例可以扫到相同 ID，头锁和状态复查保证只释放一次。
     *
     * @return 本次调用实际完成释放的预留数量
     */
    public int sweep(int maxCandidates) {
        int released = 0;
        int seen = 0;
        ExpiryCandidate cursor = null;
        Instant oldest = null;
        while (seen < maxCandidates) {
            List<ExpiryCandidate> page;
            try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.EXPIRY)) {
                page = reservations.findExpiryCandidates(cursor, Math.min(PAGE_SIZE, maxCandidates - seen));
            }
            if (page.isEmpty()) {
                break;
            }
            if (oldest == null) {
                oldest = page.get(0).expiresAt();
            }
            for (ExpiryCandidate candidate : page) {
                if (expireOne(candidate.shopId(), candidate.reservationId())) {
                    released++;
                }
            }
            seen += page.size();
            cursor = page.get(page.size() - 1);
        }
        return released;
    }

    /** 释放单个预留；临时锁失败留给下一轮扫描，不在此处无界重试。 */
    public boolean expireOne(long shopId, UUID reservationId) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            boolean released = transactions.expire(shopId, reservationId);
            outcome = released ? "released" : "skipped";
            metrics.expiryReleased(released ? 1 : 0);
            return released;
        } catch (RuntimeException e) {
            if (SqlErrors.isTransientLockFailure(e)) {
                outcome = "contended";
                return false;
            }
            if (e instanceof ApiException api) {
                outcome = api.code().name().toLowerCase();
            }
            log.error("Expiry failed for a reservation in shop {}", shopId, e);
            return false;
        } finally {
            metrics.recordOperation(BusinessOperation.EXPIRY, outcome, System.nanoTime() - start);
        }
    }
}
