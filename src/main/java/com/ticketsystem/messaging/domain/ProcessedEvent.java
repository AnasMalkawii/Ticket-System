package com.ticketsystem.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side dedupe ledger. Delivery from the broker is at-least-once; inserting this row
 * as part of handling a message makes consumption idempotent, so a redelivery fails the
 * primary key and the handler becomes a no-op (F-21).
 */
@Entity
@Table(name = "processed_event")
@IdClass(ProcessedEventId.class)
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Id
    @Column(nullable = false, length = 100)
    private String consumer;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public static ProcessedEvent of(UUID eventId, String consumer, Instant now) {
        ProcessedEvent processed = new ProcessedEvent();
        processed.eventId = eventId;
        processed.consumer = consumer;
        processed.processedAt = now;
        return processed;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getConsumer() {
        return consumer;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
