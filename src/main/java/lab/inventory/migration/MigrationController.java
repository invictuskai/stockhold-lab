package lab.inventory.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lab.inventory.api.AdminApi;
import lab.inventory.api.dto.Dtos.ClaimRequest;
import lab.inventory.api.dto.Dtos.ReserveRequest;
import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.exception.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅 migration profile：经由迁移路由的预留接口，以及切换模式、初始化旧系统、一致性检查等管理接口。
 * 迁移期间预留以幂等键标识，因为两个系统各自生成 ID，幂等键是它们共同的关联键。
 */
@RestController
@Profile("migration")
@RequestMapping("/api/v1/migration")
public class MigrationController {

    public record ModeRequest(MigrationMode mode) {
    }

    public record MigrationResponse(String idempotencyKey, MigrationMode mode, String primarySystem, String outcome,
            String status, UUID mysqlReservationId) {

        static MigrationResponse of(String idempotencyKey, MigrationService.Result result) {
            return new MigrationResponse(idempotencyKey, result.mode(), result.primarySystem(),
                    result.outcome().category().name(), result.outcome().status(), result.mysqlReservationId());
        }
    }

    private final MigrationService migration;

    public MigrationController(MigrationService migration) {
        this.migration = migration;
    }

    @PostMapping("/shops/{shopId}/reservations")
    public ResponseEntity<MigrationResponse> reserve(@PathVariable long shopId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReserveRequest request) {
        List<RawLine> lines = request.lines() == null ? null : request.lines().stream()
                .map(l -> l == null ? null : new RawLine(l.inventoryItemId(), l.locationId(), l.quantity(), l.locationIds()))
                .toList();
        MigrationService.Result result = migration.reserve(shopId, idempotencyKey, request.ttlSeconds(), lines);
        HttpStatus status = result.outcome().category() == Outcome.Category.CREATED ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(MigrationResponse.of(idempotencyKey, result));
    }

    @PostMapping("/shops/{shopId}/reservations/by-key/{key}/claim")
    public MigrationResponse claim(@PathVariable long shopId, @PathVariable String key,
            @RequestBody ClaimRequest request) {
        if (request.paymentReference() == null) {
            throw ApiException.invalidArgument("paymentReference is required.");
        }
        return MigrationResponse.of(key, migration.claim(shopId, key, request.paymentReference()));
    }

    @PostMapping("/shops/{shopId}/reservations/by-key/{key}/cancel")
    public MigrationResponse cancel(@PathVariable long shopId, @PathVariable String key) {
        return MigrationResponse.of(key, migration.cancel(shopId, key));
    }

    @GetMapping("/shops/{shopId}/mode")
    public Map<String, Object> mode(@PathVariable long shopId) {
        return Map.of("shopId", shopId, "mode", migration.mode(shopId));
    }

    @AdminApi
    @PutMapping("/shops/{shopId}/mode")
    public Map<String, Object> setMode(@PathVariable long shopId, @RequestBody ModeRequest request) {
        if (request.mode() == null) {
            throw ApiException.invalidArgument("mode is required.");
        }
        migration.setMode(shopId, request.mode());
        return mode(shopId);
    }

    /** 把旧 Redis 系统的可售计数初始化为给定值；MySQL 库存仍通过主管理接口创建。 */
    @AdminApi
    @PostMapping("/shops/{shopId}/inventory/{itemId}/locations/{locationId}/legacy-seed")
    public Map<String, Object> seedLegacy(@PathVariable long shopId, @PathVariable long itemId,
            @PathVariable long locationId, @RequestParam long available) {
        if (available < 0) {
            throw ApiException.invalidArgument("available must not be negative.");
        }
        migration.seedLegacy(new InventoryKey(shopId, itemId, locationId), available);
        return Map.of("shopId", shopId, "inventoryItemId", itemId, "locationId", locationId,
                "available", available);
    }

    /** 逐维度比较两个系统的可售量，并比较各状态的预留数量。items 为逗号分隔的商品 ID。 */
    @AdminApi
    @GetMapping("/shops/{shopId}/consistency")
    public MigrationService.Consistency consistency(@PathVariable long shopId, @RequestParam List<Long> items,
            @RequestParam long locationId) {
        List<InventoryKey> keys = new ArrayList<>();
        for (Long item : items) {
            keys.add(new InventoryKey(shopId, item, locationId));
        }
        return migration.consistency(shopId, keys);
    }

    /** 旧系统自身的账实差异：确认的两步无法原子提交，失败会在这里留下非零 drift。 */
    @AdminApi
    @GetMapping("/shops/{shopId}/legacy-drift")
    public List<LegacyRedisReservations.LegacyDrift> legacyDrift(@PathVariable long shopId,
            @RequestParam List<Long> items, @RequestParam long locationId) {
        List<InventoryKey> keys = new ArrayList<>();
        for (Long item : items) {
            keys.add(new InventoryKey(shopId, item, locationId));
        }
        return migration.legacyDrift(keys);
    }

    @AdminApi
    @GetMapping("/mismatches")
    public List<MigrationService.Mismatch> mismatches() {
        return migration.mismatches();
    }
}
