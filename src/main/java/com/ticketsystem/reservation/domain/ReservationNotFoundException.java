package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

/** Raised when a reservation identifier does not exist. */
public class ReservationNotFoundException extends DomainException {

    public ReservationNotFoundException(UUID reservationId) {
        super(ErrorCode.RESERVATION_NOT_FOUND,
                "Reservation %s does not exist.".formatted(reservationId));
    }
}
