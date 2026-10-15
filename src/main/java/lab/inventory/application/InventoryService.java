package lab.inventory.application;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import lab.inventory.application.PoolRefillService.RefillResult;
import lab.inventory.domain.CanonicalReservationRequest.Hashing;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.persistence.InventoryLedgerRepository;
import lab.inventory.persistence.InventoryLedgerRepository.LedgerRow;
import lab.inventory.persistence.InventoryOperationRepository;
import lab.inventory.persistence.InventoryOperationRepository.OperationRow;
import lab.inventory.persistence.InventorySnapshotRepository;
import lab.inventory.persistence.SqlErrors;
import org.springframework.stereotype.Service;

/** 库存初始化、调整（7.9）与一致性快照查询。锁顺序：操作头 -> ledger。 */
@Service
public class InventoryService {

    public static final int MAX_POOL_CAPACITY = 1000;
    public static final int DEFAULT_POOL_CAPACITY = 1000;

    public record OperationOutcome(OperationRow operation, boolean replayed) {
    }

    /** 操作头主键冲突：整个事务回滚后在新事务里查询已有记录。 */
    private static final class DuplicateOperationException extends RuntimeException {
        private DuplicateOperationException() {
            super(null, null, false, false);
        }
    }

    private final TransactionRunner transactions;
    private final InventoryLedgerRepository ledgers;
    private final InventoryOperationRepository operations;
    private final InventorySnapshotRepository snapshots;
    private final PoolRefillService refills;

    public InventoryService(TransactionRunner transactions, InventoryLedgerRepository ledgers,
            InventoryOperationRepository operations, InventorySnapshotRepository snapshots,
            PoolRefillService refills) {
        this.transactions = transactions;
        this.ledgers = ledgers;
        this.operations = operations;
        this.snapshots = snapshots;
        this.refills = refills;
    }

    /** 创建 H=初始数量、A=0 的账本。不预填单位池：初始化成功与池已满是两回事。 */
    public OperationOutcome create(InventoryKey key, UUID operationId, long initialQuantity, Integer poolCapacity) {
        if (operationId == null) {
            throw ApiException.invalidArgument("operationId is required.");
        }
        if (initialQuantity < 0) {
            throw ApiException.invalidArgument("initialQuantity must be >= 0.");
        }
        int capacity = poolCapacity == null ? DEFAULT_POOL_CAPACITY : poolCapacity;
        if (capacity < 1 || capacity > MAX_POOL_CAPACITY) {
            throw ApiException.invalidArgument("poolCapacity must be between 1 and " + MAX_POOL_CAPACITY + ".");
        }
        byte[] hash = Hashing.sha256("initialize:v1|shop=" + key.shopId() + "|item=" + key.inventoryItemId()
                + "|location=" + key.locationId() + "|quantity=" + initialQuantity + "|capacity=" + capacity);
        OperationRow row = new OperationRow(operationId, key, hash, InventoryOperationRepository.TYPE_INITIALIZE,
                initialQuantity, initialQuantity, 0, capacity);

        return idempotent(key.shopId(), operationId, hash, () -> {
            insertOperation(row);
            try {
                ledgers.insert(key, initialQuantity, capacity);
            } catch (RuntimeException e) {
                if (SqlErrors.isDuplicateKey(e)) {
                    throw ApiException.forKey(ErrorCode.INVENTORY_ALREADY_EXISTS,
                            "Inventory dimension already exists.", key);
                }
                throw e;
            }
            return row;
        });
    }

    /** H += delta；负向调整只允许新 H >= A，不回收池单位，也不撤销预留。 */
    public OperationOutcome adjust(InventoryKey key, UUID operationId, long deltaQuantity) {
        if (operationId == null) {
            throw ApiException.invalidArgument("operationId is required.");
        }
        if (deltaQuantity == 0) {
            throw ApiException.invalidArgument("deltaQuantity must not be zero.");
        }
        byte[] hash = Hashing.sha256("adjust:v1|shop=" + key.shopId() + "|item=" + key.inventoryItemId()
                + "|location=" + key.locationId() + "|delta=" + deltaQuantity);

        return idempotent(key.shopId(), operationId, hash, () -> {
            // 先以合法占位快照插入操作头，锁定账本后再写入最终快照；任何失败都整体回滚。
            insertOperation(new OperationRow(operationId, key, hash, InventoryOperationRepository.TYPE_ADJUST,
                    deltaQuantity, 0, 0, 1));
            LedgerRow ledger = ledgers.lockForUpdate(key).orElseThrow(() -> ApiException.inventoryNotFound(key));
            long newOnHand;
            try {
                newOnHand = Math.addExact(ledger.onHand(), deltaQuantity);
            } catch (ArithmeticException overflow) {
                throw ApiException.invalidArgument("Adjustment overflows the on-hand quantity.");
            }
            if (newOnHand < ledger.allocated()) {
                throw ApiException.forKey(ErrorCode.STOCK_ALLOCATED,
                        "On-hand quantity cannot drop below the allocated quantity.", key);
            }
            if (ledgers.updateOnHand(key, newOnHand) != 1) {
                throw ApiException.invariantViolation("Ledger rejected an adjustment under its row lock.", key);
            }
            operations.updateSnapshot(key.shopId(), operationId, newOnHand, ledger.allocated(),
                    ledger.poolCapacity());
            return new OperationRow(operationId, key, hash, InventoryOperationRepository.TYPE_ADJUST,
                    deltaQuantity, newOnHand, ledger.allocated(), ledger.poolCapacity());
        });
    }

    public InventorySnapshot snapshot(InventoryKey key) {
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.QUERY)) {
            return snapshots.snapshot(key).orElseThrow(() -> ApiException.inventoryNotFound(key));
        }
    }

    public RefillResult refill(InventoryKey key) {
        return refills.refillOneDimension(key, false);
    }

    private OperationOutcome idempotent(long shopId, UUID operationId, byte[] hash,
            java.util.function.Supplier<OperationRow> transaction) {
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.ADMIN)) {
            Optional<OperationOutcome> replay = findReplay(shopId, operationId, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
            try {
                return new OperationOutcome(transactions.executeWithRetry(BusinessOperation.ADMIN, transaction),
                        false);
            } catch (DuplicateOperationException e) {
                return findReplay(shopId, operationId, hash).orElseThrow(
                        () -> ApiException.busy("Concurrent operation with the same operationId; retry."));
            }
        }
    }

    private Optional<OperationOutcome> findReplay(long shopId, UUID operationId, byte[] hash) {
        return operations.find(shopId, operationId).map(existing -> {
            if (!Arrays.equals(existing.requestHash(), hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "operationId was already used with a different request.");
            }
            return new OperationOutcome(existing, true);
        });
    }

    private void insertOperation(OperationRow row) {
        try {
            operations.insert(row);
        } catch (RuntimeException e) {
            if (SqlErrors.isDuplicateKey(e)) {
                throw new DuplicateOperationException();
            }
            throw e;
        }
    }
}
