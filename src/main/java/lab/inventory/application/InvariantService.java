package lab.inventory.application;

import java.util.ArrayList;
import java.util.List;

import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.observability.InventoryMetrics;
import lab.inventory.persistence.InventorySnapshotRepository;
import lab.inventory.persistence.InventorySnapshotRepository.Audit;
import org.springframework.stereotype.Service;

/** 单维度只读一致性审计（总体技术方案第 11 节）；只报告，不自动修复账本。 */
@Service
public class InvariantService {

    public record Report(InventorySnapshot snapshot, List<String> violations) {

        public boolean valid() {
            return violations.isEmpty();
        }
    }

    private final InventorySnapshotRepository snapshots;
    private final InventoryMetrics metrics;

    public InvariantService(InventorySnapshotRepository snapshots, InventoryMetrics metrics) {
        this.snapshots = snapshots;
        this.metrics = metrics;
    }

    public Report check(InventoryKey key) {
        Audit audit;
        try (BusinessOperationContext.Scope ignored = BusinessOperationContext.open(BusinessOperation.ADMIN)) {
            audit = snapshots.audit(key).orElseThrow(() -> ApiException.inventoryNotFound(key));
        }
        InventorySnapshot s = audit.snapshot();
        List<String> violations = new ArrayList<>();
        if (s.allocated() < 0 || s.onHand() < s.allocated()) {
            violations.add("H >= A >= 0 violated: H=" + s.onHand() + ", A=" + s.allocated());
        }
        if (s.allocated() != s.poolAvailable() + s.activeReserved()) {
            violations.add("A = P + R violated: A=" + s.allocated() + ", P=" + s.poolAvailable()
                    + ", R=" + s.activeReserved());
        }
        if (s.poolAvailable() > s.poolCapacity()) {
            violations.add("P <= C violated: P=" + s.poolAvailable() + ", C=" + s.poolCapacity());
        }
        if (audit.orphanLines() > 0) {
            violations.add("Orphan reservation lines without a header: " + audit.orphanLines());
        }
        if (audit.operationDelta() - audit.claimedQuantity() != s.onHand()) {
            violations.add("Operations delta - CLAIMED = H violated: delta=" + audit.operationDelta()
                    + ", claimed=" + audit.claimedQuantity() + ", H=" + s.onHand());
        }
        if (!violations.isEmpty()) {
            metrics.invariantViolation();
        }
        return new Report(s, List.copyOf(violations));
    }
}
