package com.ticketsystem.order.repository;

import com.ticketsystem.order.domain.TicketOrder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketOrderRepository extends JpaRepository<TicketOrder, UUID> {

    /** At most one order per reservation, guaranteed by uq_ticket_order_reservation (I5). */
    Optional<TicketOrder> findByReservationId(UUID reservationId);
}
