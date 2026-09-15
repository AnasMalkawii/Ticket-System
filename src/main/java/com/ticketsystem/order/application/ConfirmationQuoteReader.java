package com.ticketsystem.order.application;

import com.ticketsystem.catalog.domain.Event;
import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.catalog.repository.EventRepository;
import com.ticketsystem.reservation.domain.IllegalReservationTransitionException;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationExpiredException;
import com.ticketsystem.reservation.domain.ReservationNotFoundException;
import com.ticketsystem.reservation.domain.ReservationStatus;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.security.ForbiddenOperationException;
import com.ticketsystem.shared.time.DatabaseTimeProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a quote before payment so no inventory lock is held across the gateway boundary. */
@Service
class ConfirmationQuoteReader {

    private final ReservationRepository reservations;
    private final EventRepository events;
    private final DatabaseTimeProvider databaseTime;

    ConfirmationQuoteReader(ReservationRepository reservations,
                            EventRepository events,
                            DatabaseTimeProvider databaseTime) {
        this.reservations = reservations;
        this.events = events;
        this.databaseTime = databaseTime;
    }

    @Transactional(readOnly = true)
    public ConfirmationQuote quote(UUID reservationId, UUID actorUserId) {
        Instant now = databaseTime.now();
        Reservation reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!reservation.getUserId().equals(actorUserId)) {
            throw new ForbiddenOperationException(reservationId);
        }
        if (reservation.isExpiredAt(now)) {
            throw new ReservationExpiredException(reservationId, reservation.getExpiresAt());
        }
        if (reservation.getStatus() != ReservationStatus.PENDING) {
            throw new IllegalReservationTransitionException(
                    reservationId, reservation.getStatus(), ReservationStatus.CONFIRMED);
        }

        Event event = events.findById(reservation.getEventId())
                .orElseThrow(() -> new EventNotFoundException(reservation.getEventId()));
        long amount = Math.multiplyExact(event.getPriceMinor(), reservation.getQty());
        return new ConfirmationQuote(reservationId, amount, event.getCurrency());
    }
}
