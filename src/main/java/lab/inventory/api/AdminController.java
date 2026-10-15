package lab.inventory.api;

import lab.inventory.api.dto.Dtos.InvariantCheckRequest;
import lab.inventory.api.dto.Dtos.InvariantCheckResponse;
import lab.inventory.application.InvariantService;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.exception.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AdminApi
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final InvariantService invariants;

    public AdminController(InvariantService invariants) {
        this.invariants = invariants;
    }

    /** 一次只检查一个维度，避免无界全表扫描；valid=false 仍返回 200。 */
    @PostMapping("/invariants/check")
    public InvariantCheckResponse check(@RequestBody InvariantCheckRequest request) {
        if (request.shopId() == null || request.inventoryItemId() == null || request.locationId() == null) {
            throw ApiException.invalidArgument("shopId, inventoryItemId and locationId are required.");
        }
        return InvariantCheckResponse.of(invariants.check(
                new InventoryKey(request.shopId(), request.inventoryItemId(), request.locationId())));
    }
}
