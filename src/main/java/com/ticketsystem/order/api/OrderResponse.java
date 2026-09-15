package com.ticketsystem.order.api;

import com.ticketsystem.order.domain.TicketOrder;
import java.time.Instant;
import java.util.UUID;

/** Public order representation matching the OpenAPI contract. */
public record OrderResponse(UUID id,
                            UUID reservationId,
                            String status,
                            long amountMinor,
                            String currency,
                            Instant createdAt) {

    public static OrderResponse from(TicketOrder order) {
        return new OrderResponse(
                order.getId(),
                order.getReservationId(),
                order.getStatus().name(),
                order.getAmountMinor(),
                order.getCurrency(),
                order.getCreatedAt());
    }
}
