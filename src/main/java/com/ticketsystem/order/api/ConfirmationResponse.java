package com.ticketsystem.order.api;

import com.ticketsystem.order.application.ConfirmationResult;
import com.ticketsystem.reservation.api.ReservationResponse;

/** Reservation and order returned after their shared transaction commits. */
public record ConfirmationResponse(ReservationResponse reservation, OrderResponse order) {

    public static ConfirmationResponse from(ConfirmationResult result) {
        return new ConfirmationResponse(
                ReservationResponse.from(result.reservation()),
                OrderResponse.from(result.order()));
    }
}
