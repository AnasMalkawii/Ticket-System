package com.ticketsystem.reservation.application;

/** Result or isolated domain rejection for one item in a committed reservation batch. */
public record BatchReservationOutcome(ReserveTicketsResult result, RuntimeException failure) {

    public static BatchReservationOutcome succeeded(ReserveTicketsResult result) {
        return new BatchReservationOutcome(result, null);
    }

    public static BatchReservationOutcome failed(RuntimeException failure) {
        return new BatchReservationOutcome(null, failure);
    }
}
