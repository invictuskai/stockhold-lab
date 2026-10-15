package lab.inventory.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import lab.inventory.config.StockholdProperties;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.Codec;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.persistence.InventoryLedgerRepository.LedgerRow;
import lab.inventory.persistence.ReservationUnitRepository;
import org.springframework.stereotype.Service;

/** 池补充协议（总体技术方案 7.4）：锁序固定为 ledger -> units，每个事务只处理一个维度。 */
@Service
public class PoolRefillService {

    /**
     * @param observedPoolBefore 账本锁下普通 COUNT 得到的池行数，可能因未提交删除而偏高
     * @param unallocatedBefore  补充前的 F = H - A
     */
    public record RefillResult(InventoryKey key, int insertedUnits, long observedPoolBefore,
            long unallocatedBefore, long allocatedAfter, int poolCapacity) {

        /** 账本锁下观察到的当前可预留上界；小于需求量才能判定真实不足。 */
        public long potentialAvailable() {
            return unallocatedBefore + observedPoolBefore;
        }
    }

    private final TransactionRunner transactions;
    private final InventoryLedgerRepository ledgers;
    private final ReservationUnitRepository units;
    private final FaultInjector faults;
    private final InventoryMetrics metrics;
    private final int insertBatchSize;

    public PoolRefillService(TransactionRunner transactions, InventoryLedgerRepository ledgers,
            ReservationUnitRepository units, FaultInjector faults, InventoryMetrics metrics,
            StockholdProperties properties) {
        this.transactions = transactions;
        this.ledgers = ledgers;
        this.units = units;
        this.faults = faults;
        this.metrics = metrics;
        this.insertBatchSize = Math.max(1, properties.refill().insertBatchSize());
    }

    /**
     * 一个完整的补充事务。返回值只描述该时刻的观察，不承诺后续请求一定能领取成功。
     *
     * @param synchronous true 表示由 reserve 缺行触发，仅用于指标区分
     */
    public RefillResult refillOneDimension(InventoryKey key, boolean synchronous) {
        RefillResult result = transactions.executeWithRetry(BusinessOperation.REFILL, () -> refillInTransaction(key));
        metrics.refill(synchronous, result.insertedUnits());
        return result;
    }

    private RefillResult refillInTransaction(InventoryKey key) {
        LedgerRow ledger = ledgers.lockForUpdate(key).orElseThrow(() -> ApiException.inventoryNotFound(key));

        // 持有账本锁后，其他补充 / 确认 / 释放 / 调整被串行化；reserve 只会让已提交的池行数减少。
        // 因此这个不加锁的 COUNT 只可能偏高，最多少补，不会让池超过容量。
        long observedPool = units.count(key);
        faults.at(FaultInjector.Point.REFILL_AFTER_COUNT);

        long unallocated = ledger.unallocated();
        int refill = (int) Math.max(0, Math.min(ledger.poolCapacity() - observedPool, unallocated));
        if (refill > 0) {
            if (ledgers.increaseAllocated(key, refill) != 1) {
                throw ApiException.invariantViolation("Ledger refused an allocation within H - A.", key);
            }
            faults.at(FaultInjector.Point.REFILL_AFTER_ALLOCATED_UPDATE);
            insertUnits(key, refill);
        }
        return new RefillResult(key, refill, observedPool, unallocated,
                ledger.allocated() + refill, ledger.poolCapacity());
    }

    private void insertUnits(InventoryKey key, int count) {
        List<byte[]> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(Codec.uuidToBytes(UUID.randomUUID()));
        }
        // 随机 UUID 按完整主键顺序插入；ID 碰撞会以重复键异常让整个事务失败，不忽略。
        ids.sort(Codec::compareUnsigned);
        for (int from = 0; from < count; from += insertBatchSize) {
            List<byte[]> batch = ids.subList(from, Math.min(count, from + insertBatchSize));
            if (units.insertBatch(key, batch) != batch.size()) {
                throw ApiException.invariantViolation("Unit insert count does not match the allocation.", key);
            }
            if (from == 0) {
                faults.at(FaultInjector.Point.REFILL_AFTER_FIRST_BATCH);
            }
        }
    }
}
