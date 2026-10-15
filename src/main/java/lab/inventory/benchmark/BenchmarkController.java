package lab.inventory.benchmark;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import lab.inventory.api.AdminApi;
import lab.inventory.api.dto.Dtos.ClaimRequest;
import lab.inventory.api.dto.Dtos.ReservationResponse;
import lab.inventory.api.dto.Dtos.ReserveRequest;
import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.benchmark.BaselineCounterService.CounterRow;
import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅 benchmark profile：单计数器对照组接口与连接持有注入（实现与验证计划 6.1 / 6.3）。 */
@RestController
@Profile("benchmark")
@RequestMapping("/api/v1/benchmark")
public class BenchmarkController {

    private static final long MAX_HOLD_MILLIS = 1000;

    private final BaselineCounterService baseline;
    private final DataSource dataSource;

    public BenchmarkController(BaselineCounterService baseline, DataSource dataSource) {
        this.baseline = baseline;
        this.dataSource = dataSource;
    }

    @AdminApi
    @PostMapping("/counter/shops/{shopId}/inventory/{itemId}/locations/{locationId}/seed")
    public CounterRow seed(@PathVariable long shopId, @PathVariable long itemId, @PathVariable long locationId,
            @RequestParam long onHand) {
        InventoryKey key = new InventoryKey(shopId, itemId, locationId);
        baseline.seed(key, onHand);
        return baseline.counter(key);
    }

    @GetMapping("/counter/shops/{shopId}/inventory/{itemId}/locations/{locationId}")
    public CounterRow counter(@PathVariable long shopId, @PathVariable long itemId, @PathVariable long locationId) {
        return baseline.counter(new InventoryKey(shopId, itemId, locationId));
    }

    @PostMapping("/counter/shops/{shopId}/reservations")
    public ResponseEntity<ReservationResponse> reserve(@PathVariable long shopId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReserveRequest request) {
        List<RawLine> lines = request.lines() == null ? null : request.lines().stream()
                .map(l -> l == null ? null : new RawLine(l.inventoryItemId(), l.locationId(), l.quantity()))
                .toList();
        ReserveOutcome outcome = baseline.reserve(shopId, idempotencyKey, request.ttlSeconds(), lines);
        ReservationResponse body = ReservationResponse.of(outcome.reservation());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @PostMapping("/counter/shops/{shopId}/reservations/{reservationId}/claim")
    public ReservationResponse claim(@PathVariable long shopId, @PathVariable UUID reservationId,
            @RequestBody ClaimRequest request) {
        return ReservationResponse.of(baseline.claim(shopId, reservationId, request.paymentReference()));
    }

    @PostMapping("/counter/shops/{shopId}/reservations/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable long shopId, @PathVariable UUID reservationId) {
        return ReservationResponse.of(baseline.cancel(shopId, reservationId));
    }

    /**
     * 制造连接瓶颈：mode=connection 获取连接后持有指定时间但不执行 SQL；
     * mode=outside 是等长的事务外等待对照。时长有上限，不接受任何 SQL 输入。
     */
    @AdminApi
    @PostMapping("/hold")
    public Map<String, Object> hold(@RequestParam(defaultValue = "connection") String mode,
            @RequestParam(defaultValue = "100") long millis) throws InterruptedException {
        if (millis < 0 || millis > MAX_HOLD_MILLIS) {
            throw ApiException.invalidArgument("millis must be between 0 and " + MAX_HOLD_MILLIS + ".");
        }
        switch (mode) {
            case "connection" -> {
                try (BusinessOperationContext.Scope ignored =
                        BusinessOperationContext.open(BusinessOperation.BENCHMARK);
                        Connection connection = dataSource.getConnection()) {
                    Thread.sleep(millis);
                } catch (SQLException e) {
                    throw new CannotGetJdbcConnectionException("Could not obtain a connection to hold", e);
                }
            }
            case "outside" -> Thread.sleep(millis);
            default -> throw ApiException.invalidArgument("mode must be 'connection' or 'outside'.");
        }
        return Map.of("mode", mode, "heldMillis", millis);
    }
}
