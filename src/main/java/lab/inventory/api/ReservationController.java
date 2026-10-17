package lab.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import lab.inventory.api.dto.Dtos.ClaimRequest;
import lab.inventory.api.dto.Dtos.ReservationResponse;
import lab.inventory.api.dto.Dtos.ReserveRequest;
import lab.inventory.application.ReservationFacade;
import lab.inventory.application.ReservationFacade.ReserveOutcome;
import lab.inventory.application.ReplicaReads;
import lab.inventory.domain.Reservation;
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
    private final ReplicaReads replicaReads;

    public ReservationController(ReservationFacade reservations, ReplicaReads replicaReads) {
        this.reservations = reservations;
        this.replicaReads = replicaReads;
    }

    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@PathVariable long shopId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReserveRequest request) {
        List<RawLine> lines = request.lines() == null ? null : request.lines().stream()
                .map(l -> l == null ? null : new RawLine(l.inventoryItemId(), l.locationId(), l.quantity(), l.locationIds()))
                .toList();
        ReserveOutcome outcome = reservations.reserve(shopId, idempotencyKey, request.ttlSeconds(), lines);
        ReservationResponse body = ReservationResponse.of(outcome.reservation());
        if (outcome.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
        }
        URI location = URI.create("/api/v1/shops/" + shopId + "/reservations/" + body.reservationId());
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(body);
    }

    /** consistency=eventual 时允许从只读副本读取（刚创建的预留可能暂时查不到）；默认 strong 读主库。 */
    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationResponse> get(@PathVariable long shopId, @PathVariable UUID reservationId,
            @RequestParam(required = false) String consistency) {
        return respond(replicaReads.reservation(shopId, reservationId, ReplicaReads.Consistency.parse(consistency)));
    }

    @GetMapping("/by-idempotency-key")
    public ResponseEntity<ReservationResponse> getByIdempotencyKey(@PathVariable long shopId,
            @RequestParam("key") String key, @RequestParam(required = false) String consistency) {
        return respond(replicaReads.reservationByKey(shopId, key, ReplicaReads.Consistency.parse(consistency)));
    }

    private static ResponseEntity<ReservationResponse> respond(ReplicaReads.Read<Reservation> read) {
        return ResponseEntity.ok().header("X-Read-Source", read.source()).body(ReservationResponse.of(read.value()));
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
