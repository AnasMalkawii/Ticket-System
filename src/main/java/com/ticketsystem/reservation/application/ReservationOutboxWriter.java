package com.ticketsystem.reservation.application;

import com.ticketsystem.messaging.domain.OutboxEvent;
import com.ticketsystem.messaging.repository.OutboxEventRepository;
import com.ticketsystem.order.domain.TicketOrder;
import com.ticketsystem.reservation.domain.Reservation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes reservation lifecycle events inside the transaction that changed the reservation. */
@Component
public class ReservationOutboxWriter {

    private static final String AGGREGATE_TYPE = "reservation";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public ReservationOutboxWriter(OutboxEventRepository outboxEventRepository,
                                   ObjectMapper objectMapper) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public void created(Reservation reservation, String correlationId, Instant now) {
        write(reservation, "reservation.created", correlationId, now);
    }

    public void cancelled(Reservation reservation, String correlationId, Instant now) {
        write(reservation, "reservation.cancelled", correlationId, now);
    }

    public void confirmed(Reservation reservation, TicketOrder order,
                          String correlationId, Instant now) {
        Map<String, Object> payload = payloadFor(reservation);
        payload.put("orderId", order.getId().toString());
        payload.put("amountMinor", order.getAmountMinor());
        payload.put("currency", order.getCurrency());
        payload.put("paymentReference", order.getPaymentReference());
        write(reservation, "reservation.confirmed", payload, correlationId, now);
    }

    public void expired(Reservation reservation, String correlationId, Instant now) {
        write(reservation, "reservation.expired", correlationId, now);
    }

    private void write(Reservation reservation, String eventType, String correlationId, Instant now) {
        write(reservation, eventType, payloadFor(reservation), correlationId, now);
    }

    private void write(Reservation reservation, String eventType, Map<String, Object> payload,
                       String correlationId, Instant now) {
        outboxEventRepository.save(OutboxEvent.pending(
                AGGREGATE_TYPE,
                reservation.getId(),
                eventType,
                objectMapper.writeValueAsString(payload),
                correlationId,
                now));
    }

    private Map<String, Object> payloadFor(Reservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.getId().toString());
        payload.put("eventId", reservation.getEventId().toString());
        payload.put("userId", reservation.getUserId().toString());
        payload.put("quantity", reservation.getQty());
        payload.put("status", reservation.getStatus().name());
        payload.put("expiresAt", reservation.getExpiresAt().toString());
        if (reservation.getTerminatedAt() != null) {
            payload.put("terminatedAt", reservation.getTerminatedAt().toString());
        }
        return payload;
    }
}
