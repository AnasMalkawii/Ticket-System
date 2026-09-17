package com.ticketsystem.order.application;

import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.inventory.domain.TicketInventory;
import com.ticketsystem.inventory.repository.TicketInventoryRepository;
import com.ticketsystem.order.domain.TicketOrder;
import com.ticketsystem.order.repository.TicketOrderRepository;
import com.ticketsystem.reservation.application.ReservationOutboxWriter;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationNotFoundException;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.auth.exception.ForbiddenOperationException;
import com.ticketsystem.shared.time.DatabaseTimeProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Locked state transition, order creation, inventory movement, and outbox insert: one commit. */
@Service
class ConfirmReservationTransaction {

    private final ReservationRepository reservations;
    private final TicketInventoryRepository inventories;
    private final TicketOrderRepository orders;
    private final ReservationOutboxWriter outboxWriter;
    private final DatabaseTimeProvider databaseTime;

    ConfirmReservationTransaction(ReservationRepository reservations,
                                  TicketInventoryRepository inventories,
                                  TicketOrderRepository orders,
                                  ReservationOutboxWriter outboxWriter,
                                  DatabaseTimeProvider databaseTime) {
        this.reservations = reservations;
        this.inventories = inventories;
        this.orders = orders;
        this.outboxWriter = outboxWriter;
        this.databaseTime = databaseTime;
    }

    @Transactional
    public ConfirmationResult confirm(ConfirmationQuote quote,
                                      PaymentAuthorization payment,
                                      UUID actorUserId,
                                      String correlationId) {
        Instant now = databaseTime.now();
        Reservation reservation = reservations.findWithLockById(quote.reservationId())
                .orElseThrow(() -> new ReservationNotFoundException(quote.reservationId()));
        if (!reservation.getUserId().equals(actorUserId)) {
            throw new ForbiddenOperationException(reservation.getId());
        }
        reservation.confirm(now);

        TicketInventory inventory = inventories
                .findWithLockByEventId(reservation.getEventId())
                .orElseThrow(() -> new EventNotFoundException(reservation.getEventId()));
        inventory.confirmHold(reservation.getQty(), now);

        TicketOrder order = orders.saveAndFlush(TicketOrder.paid(
                reservation.getId(), reservation.getUserId(), quote.amountMinor(),
                quote.currency(), payment.reference(), now));
        outboxWriter.confirmed(reservation, order, correlationId, now);
        return new ConfirmationResult(reservation, order);
    }
}
