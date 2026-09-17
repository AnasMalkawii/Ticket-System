package com.ticketsystem.reservation.application;

import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.inventory.domain.TicketInventory;
import com.ticketsystem.inventory.repository.TicketInventoryRepository;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationNotFoundException;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.auth.exception.ForbiddenOperationException;
import com.ticketsystem.shared.time.DatabaseTimeProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cancels a live hold and returns its tickets in one reservation-first transaction. */
@Service
public class CancelReservationService {

    private final ReservationRepository reservationRepository;
    private final TicketInventoryRepository inventoryRepository;
    private final DatabaseTimeProvider databaseTime;
    private final ReservationOutboxWriter outboxWriter;

    public CancelReservationService(ReservationRepository reservationRepository,
                                    TicketInventoryRepository inventoryRepository,
                                    DatabaseTimeProvider databaseTime,
                                    ReservationOutboxWriter outboxWriter) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.databaseTime = databaseTime;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public Reservation cancel(UUID reservationId, UUID actorUserId, String correlationId) {
        Instant now = databaseTime.now();

        // Global lifecycle lock order: reservation first, then its inventory row. If the
        // expiry worker won, this read resumes with EXPIRED and cancel() rejects before any
        // inventory counter can move again.
        Reservation reservation = reservationRepository.findWithLockById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!reservation.getUserId().equals(actorUserId)) {
            throw new ForbiddenOperationException(reservationId);
        }
        reservation.cancel(now);

        TicketInventory inventory = inventoryRepository
                .findWithLockByEventId(reservation.getEventId())
                .orElseThrow(() -> new EventNotFoundException(reservation.getEventId()));
        inventory.releaseHold(reservation.getQty(), now);

        outboxWriter.cancelled(reservation, correlationId, now);
        return reservation;
    }
}
