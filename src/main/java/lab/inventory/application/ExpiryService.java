package lab.inventory.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.ReservationRepository;
import lab.inventory.persistence.ReservationRepository.ExpiryCandidate;
import lab.inventory.persistence.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 到期释放（7.8）：非锁定扫描候选，每个候选一个独立短事务，持头锁后复查。 */
@Service
public class ExpiryService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);
    private static final int PAGE_SIZE = 100;

    private final ReservationTransactionService transactions;
    private final ReservationRepository reservations;
    private final InventoryMetrics metrics;

    public ExpiryService(ReservationTransactionService transactions, ReservationRepository reservations,
            InventoryMetrics metrics) {
        this.transactions = transactions;
        this.reservations = reservations;
        this.metrics = metrics;
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
        // 监控最老未释放到期时间，而不只是 job 有没有报错。
        metrics.expiryScan(oldest == null ? 0 : Math.max(0, Duration.between(oldest, Instant.now()).toMillis()), seen);
        return released;
    }

    /** 释放单个预留；临时锁失败留给下一轮扫描，不在此处无界重试。 */
    public boolean expireOne(long shopId, UUID reservationId) {
        long start = System.nanoTime();
        String outcome = "error";
        try {
            boolean released = transactions.expire(shopId, reservationId);
            outcome = released ? "released" : "skipped";
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
