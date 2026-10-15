package lab.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import lab.inventory.api.dto.Dtos.ClaimRequest;
import lab.inventory.api.dto.Dtos.ReservationResponse;
import lab.inventory.api.dto.Dtos.ReserveRequest;
import lab.inventory.application.ReservationFacade;
import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops/{shopId}/reservations")
public class ReservationController {

    private final ReservationFacade reservations;

    public ReservationController(ReservationFacade reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@PathVariable long shopId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReserveRequest request) {
        List<RawLine> lines = request.lines() == null ? null : request.lines().stream()
                .map(l -> l == null ? null : new RawLine(l.inventoryItemId(), l.locationId(), l.quantity()))
                .toList();
        ReserveOutcome outcome = reservations.reserve(shopId, idempotencyKey, request.ttlSeconds(), lines);
        ReservationResponse body = ReservationResponse.of(outcome.reservation());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        URI location = URI.create("/api/v1/shops/" + shopId + "/reservations/" + body.reservationId());
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(body);
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse get(@PathVariable long shopId, @PathVariable UUID reservationId) {
        return ReservationResponse.of(reservations.get(shopId, reservationId));
    }

    @GetMapping("/by-idempotency-key")
    public ReservationResponse getByIdempotencyKey(@PathVariable long shopId, @RequestParam("key") String key) {
        return ReservationResponse.of(reservations.getByIdempotencyKey(shopId, key));
    }

    @PostMapping("/{reservationId}/claim")
    public ReservationResponse claim(@PathVariable long shopId, @PathVariable UUID reservationId,
            @RequestBody ClaimRequest request) {
        if (request.paymentReference() == null) {
            throw ApiException.invalidArgument("paymentReference is required.");
        }
        return ReservationResponse.of(reservations.claim(shopId, reservationId, request.paymentReference()));
    }

    @PostMapping("/{reservationId}/cancel")
    public ReservationResponse cancel(@PathVariable long shopId, @PathVariable UUID reservationId) {
        return ReservationResponse.of(reservations.cancel(shopId, reservationId));
    }
}
