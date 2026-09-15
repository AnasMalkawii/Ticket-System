package com.ticketsystem.messaging.publisher;

import com.ticketsystem.messaging.config.MessagingProperties;
import com.ticketsystem.messaging.config.RabbitTopology;
import com.ticketsystem.messaging.domain.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Sends one durable outbox envelope and returns only after a positive broker confirm. */
@Component
@ConditionalOnProperty(prefix = "ticketing.messaging", name = "enabled", havingValue = "true")
public class RabbitOutboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final MessagingProperties properties;

    public RabbitOutboxPublisher(RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper,
                                 MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(OutboxEvent event) {
        CorrelationData correlation = new CorrelationData(event.getId().toString());
        rabbitTemplate.send(
                RabbitTopology.EVENTS_EXCHANGE,
                event.getType(),
                toMessage(event),
                correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(
                    properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for RabbitMQ acknowledgement", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "RabbitMQ acknowledgement did not arrive before the timeout", failure);
        }

        if (!confirm.ack()) {
            throw new IllegalStateException(
                    "RabbitMQ negatively acknowledged event %s: %s"
                            .formatted(event.getId(), confirm.reason()));
        }
        if (correlation.getReturned() != null) {
            throw new IllegalStateException(
                    "RabbitMQ returned unroutable event %s: %s"
                            .formatted(event.getId(), correlation.getReturned().getReplyText()));
        }
    }

    private Message toMessage(OutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.getId().toString());
        envelope.put("eventType", event.getType());
        envelope.put("aggregateType", event.getAggregateType());
        envelope.put("aggregateId", event.getAggregateId().toString());
        envelope.put("occurredAt", event.getCreatedAt().toString());
        envelope.put("correlationId", event.getCorrelationId());
        envelope.put("payload", objectMapper.readTree(event.getPayload()));

        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(event.getId().toString());
        messageProperties.setCorrelationId(event.getCorrelationId());
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setHeader("eventId", event.getId().toString());
        messageProperties.setHeader("eventType", event.getType());
        return new Message(objectMapper.writeValueAsBytes(envelope), messageProperties);
    }
}
