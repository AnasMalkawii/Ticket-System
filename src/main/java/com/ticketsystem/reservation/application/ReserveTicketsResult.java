package com.ticketsystem.reservation.application;

import com.ticketsystem.reservation.domain.Reservation;

/**
 * Result of a reserve request, including the HTTP metadata needed for idempotent replay.
 *
 * @param reservation the one logical reservation represented by the idempotency key
 * @param replayed     whether this request returned a reservation created by an earlier call
 */
public record ReserveTicketsResult(Reservation reservation, boolean replayed) {

    public static ReserveTicketsResult created(Reservation reservation) {
        return new ReserveTicketsResult(reservation, false);
    }

    public static ReserveTicketsResult replayed(Reservation reservation) {
        return new ReserveTicketsResult(reservation, true);
    }
}
