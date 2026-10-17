package lab.inventory.api;

import lab.inventory.api.dto.Dtos.AdjustInventoryRequest;
import lab.inventory.api.dto.Dtos.CreateInventoryRequest;
import lab.inventory.api.dto.Dtos.InventorySnapshotResponse;
import lab.inventory.api.dto.Dtos.OperationResponse;
import lab.inventory.api.dto.Dtos.RefillResponse;
import lab.inventory.application.InventoryService;
import lab.inventory.application.InventoryService.OperationOutcome;
import lab.inventory.application.ReplicaReads;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.InventorySnapshot;
import lab.inventory.domain.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/inventory")
public class InventoryController {

    private final InventoryService inventory;
    private final ReplicaReads replicaReads;

    public InventoryController(InventoryService inventory, ReplicaReads replicaReads) {
        this.inventory = inventory;
        this.replicaReads = replicaReads;
    }

    @AdminApi
    @PostMapping
    public ResponseEntity<OperationResponse> create(@PathVariable long shopId,
            @RequestBody CreateInventoryRequest request) {
        if (request.inventoryItemId() == null || request.locationId() == null
                || request.initialQuantity() == null) {
            throw ApiException.invalidArgument("inventoryItemId, locationId and initialQuantity are required.");
        }
        InventoryKey key = new InventoryKey(shopId, request.inventoryItemId(), request.locationId());
        OperationOutcome outcome = inventory.create(key, request.operationId(), request.initialQuantity(),
                request.poolCapacity());
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(OperationResponse.of(outcome.operation()));
    }

    @AdminApi
    @PostMapping("/{itemId}/locations/{locationId}/adjustments")
    public OperationResponse adjust(@PathVariable long shopId, @PathVariable long itemId,
            @PathVariable long locationId, @RequestBody AdjustInventoryRequest request) {
        if (request.deltaQuantity() == null) {
            throw ApiException.invalidArgument("deltaQuantity is required.");
        }
        OperationOutcome outcome = inventory.adjust(new InventoryKey(shopId, itemId, locationId),
                request.operationId(), request.deltaQuantity());
        return OperationResponse.of(outcome.operation());
    }

    /** consistency=eventual 时允许从只读副本读取（可能落后于主库）；默认 strong 读主库。 */
    @GetMapping("/{itemId}/locations/{locationId}")
    public ResponseEntity<InventorySnapshotResponse> snapshot(@PathVariable long shopId, @PathVariable long itemId,
            @PathVariable long locationId, @RequestParam(required = false) String consistency) {
        ReplicaReads.Read<InventorySnapshot> read = replicaReads.snapshot(new InventoryKey(shopId, itemId, locationId),
                ReplicaReads.Consistency.parse(consistency));
        return ResponseEntity.ok().header("X-Read-Source", read.source())
                .body(InventorySnapshotResponse.of(read.value()));
    }

    @AdminApi
    @PostMapping("/{itemId}/locations/{locationId}/refill")
    public RefillResponse refill(@PathVariable long shopId, @PathVariable long itemId,
            @PathVariable long locationId) {
        return RefillResponse.of(inventory.refill(new InventoryKey(shopId, itemId, locationId)));
    }
}
