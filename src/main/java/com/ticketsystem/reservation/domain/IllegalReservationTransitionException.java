package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

/**
 * Raised when a transition is attempted that the state machine does not permit - most often
 * because another transaction (a cancellation, or the expiry worker) already moved the
 * reservation to a terminal state.
 *
 * <p>This is reported as {@code 409 INVALID_STATE} rather than being swallowed, so a
 * duplicate or racing transition is observable in metrics instead of silently succeeding.
 */
public class IllegalReservationTransitionException extends DomainException {

    private final UUID reservationId;
    private final ReservationStatus from;
    private final ReservationStatus to;

    public IllegalReservationTransitionException(UUID reservationId, ReservationStatus from,
                                                 ReservationStatus to) {
        super(ErrorCode.INVALID_STATE,
                "Reservation %s cannot move from %s to %s; legal transitions from %s are %s"
                        .formatted(reservationId, from, to, from, from.allowedTransitions()));
        this.reservationId = reservationId;
        this.from = from;
        this.to = to;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public ReservationStatus getFrom() {
        return from;
    }

    public ReservationStatus getTo() {
        return to;
    }
}
