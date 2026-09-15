package com.ticketsystem.order.application;

import com.ticketsystem.order.domain.TicketOrder;
import com.ticketsystem.reservation.domain.Reservation;

/** Reservation and order committed atomically by confirmation. */
public record ConfirmationResult(Reservation reservation, TicketOrder order) {
}
