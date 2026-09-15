package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;

/**
 * Raised when a hold is confirmed after its deadline has passed. Distinct from
 * {@link IllegalReservationTransitionException}: the reservation may still be {@code PENDING}
 * in the database because the expiry worker has not reached it yet, but it is no longer
 * confirmable. Reported as {@code 409 RESERVATION_EXPIRED}.
 */
public class ReservationExpiredException extends DomainException {

    private final UUID reservationId;
    private final Instant expiresAt;

    public ReservationExpiredException(UUID reservationId, Instant expiresAt) {
        super(ErrorCode.RESERVATION_EXPIRED,
                "Reservation %s expired at %s".formatted(reservationId, expiresAt));
        this.reservationId = reservationId;
        this.expiresAt = expiresAt;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
