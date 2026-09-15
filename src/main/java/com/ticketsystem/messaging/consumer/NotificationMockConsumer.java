package com.ticketsystem.messaging.consumer;

import com.ticketsystem.messaging.config.RabbitTopology;
import com.ticketsystem.shared.api.RequestCorrelation;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Rabbit adapter for the idempotent notification mock. Malformed messages enter retry/DLQ. */
@Component
@ConditionalOnProperty(prefix = "ticketing.messaging", name = "enabled", havingValue = "true")
public class NotificationMockConsumer {

    private final NotificationMockHandler handler;

    public NotificationMockConsumer(NotificationMockHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RabbitTopology.NOTIFICATION_QUEUE)
    public void consume(Message message) {
        String eventId = message.getMessageProperties().getMessageId();
        String eventType = message.getMessageProperties().getHeader("eventType");
        if (eventId == null || !"reservation.confirmed".equals(eventType)) {
            throw new IllegalArgumentException(
                    "A reservation.confirmed message with a UUID messageId is required");
        }
        String correlationId = message.getMessageProperties().getCorrelationId();
        try (RequestCorrelation.Scope ignored = RequestCorrelation.open(
                correlationId == null ? "message-" + eventId : correlationId)) {
            handler.handle(UUID.fromString(eventId));
        }
    }
}
