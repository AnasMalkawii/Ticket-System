package com.ticketsystem.reservation.api;

import com.ticketsystem.reservation.application.CancelReservationService;
import com.ticketsystem.reservation.application.GetReservationService;
import com.ticketsystem.reservation.application.IdempotencyKeyPolicy;
import com.ticketsystem.reservation.application.ReserveTicketsCommand;
import com.ticketsystem.reservation.application.ReserveTicketsResult;
import com.ticketsystem.reservation.application.ReservationRequestService;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.InvalidQuantityException;
import com.ticketsystem.security.AuthenticatedUser;
import com.ticketsystem.security.Role;
import com.ticketsystem.shared.api.RequestCorrelation;
import com.ticketsystem.shared.config.TicketingProperties;
import com.ticketsystem.shared.error.InvalidRequestException;
import com.ticketsystem.shared.ratelimit.ReserveRateLimiter;
import com.ticketsystem.order.api.ConfirmReservationRequest;
import com.ticketsystem.order.api.ConfirmationResponse;
import com.ticketsystem.order.application.ConfirmReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReservationController {

    private final ReservationRequestService reserveTicketsService;
    private final CancelReservationService cancelReservationService;
    private final GetReservationService getReservationService;
    private final ReserveRateLimiter reserveRateLimiter;
    private final TicketingProperties properties;
    private final ConfirmReservationService confirmReservationService;

    public ReservationController(ReservationRequestService reserveTicketsService,
                                 CancelReservationService cancelReservationService,
                                 GetReservationService getReservationService,
                                 ReserveRateLimiter reserveRateLimiter,
                                 TicketingProperties properties,
                                 ConfirmReservationService confirmReservationService) {
        this.reserveTicketsService = reserveTicketsService;
        this.cancelReservationService = cancelReservationService;
        this.getReservationService = getReservationService;
        this.reserveRateLimiter = reserveRateLimiter;
        this.properties = properties;
        this.confirmReservationService = confirmReservationService;
    }

    /**
     * Creates a hold on tickets for an event.
     *
     * <p>{@code Idempotency-Key} is mandatory: a client that times out cannot tell whether its
     * request committed, and the key is what lets the retry resolve that ambiguity (F-02).
     * An identical retry returns the reservation already owned by the key, while reuse for a
     * different request is rejected. PostgreSQL arbitrates concurrent callers across replicas.
     *
     * <p>The acting user is derived exclusively from the verified JWT. The former
     * {@code X-User-Id} development header is explicitly rejected.
     */
    @PostMapping("/events/{eventId}/reservations")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID eventId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(name = "X-User-Id", required = false) String legacyUserId,
            @RequestHeader(RequestCorrelation.HEADER_NAME) String requestId,
            @Valid @RequestBody CreateReservationRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        if (legacyUserId != null) {
            throw new InvalidRequestException(
                    "X-User-Id is not accepted; identity comes from the bearer token.");
        }
        AuthenticatedUser actor = AuthenticatedUser.from(authentication);
        UUID userId = actor.userId();
        int quantity = request.quantity();
        int maximum = properties.reservation().maxQuantityPerRequest();
        if (quantity > maximum) {
            throw new InvalidQuantityException(quantity, maximum);
        }
        IdempotencyKeyPolicy.validate(idempotencyKey);
        reserveRateLimiter.check(userId, httpRequest.getRemoteAddr());

        ReserveTicketsCommand command = new ReserveTicketsCommand(
                eventId, userId, quantity, idempotencyKey, requestId);

        ReserveTicketsResult result = reserveTicketsService.reserveWithResult(command);
        Reservation reservation = result.reservation();

        ResponseEntity.BodyBuilder response = ResponseEntity
                .created(URI.create("/api/v1/reservations/" + reservation.getId()));
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(ReservationResponse.from(reservation));
    }

    /** Returns reservation state to its owner, or to an administrator performing support. */
    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationResponse> get(
            @PathVariable UUID reservationId,
            Authentication authentication) {
        AuthenticatedUser actor = AuthenticatedUser.from(authentication);
        Reservation reservation = getReservationService.get(
                reservationId, actor.userId(), actor.role() == Role.ADMIN);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    /** Cancels a live hold. A repeated or racing terminal transition is a safe 409 no-op. */
    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable UUID reservationId,
            @RequestHeader(RequestCorrelation.HEADER_NAME) String requestId,
            Authentication authentication) {

        AuthenticatedUser actor = AuthenticatedUser.from(authentication);
        Reservation reservation = cancelReservationService.cancel(
                reservationId, actor.userId(), requestId);
        return ResponseEntity.ok(ReservationResponse.from(reservation));
    }

    /** Confirms a live hold; RabbitMQ is deliberately absent from this request transaction. */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ResponseEntity<ConfirmationResponse> confirm(
            @PathVariable UUID reservationId,
            @RequestHeader(RequestCorrelation.HEADER_NAME) String requestId,
            @Valid @RequestBody(required = false) ConfirmReservationRequest request,
            Authentication authentication) {

        AuthenticatedUser actor = AuthenticatedUser.from(authentication);
        String paymentToken = request == null ? "tok_ok" : request.tokenOrDefault();
        return ResponseEntity.ok(ConfirmationResponse.from(
                confirmReservationService.confirm(
                        reservationId, actor.userId(), paymentToken, requestId)));
    }
}
