package com.ticketsystem.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * An event awaiting publication to the broker.
 *
 * <p>Written by the same transaction that changed reservation or order state, which is what
 * removes the dual write between database and broker. The publisher marks a row published
 * only after broker acknowledgement, so a broker outage delays events rather than losing
 * them (F-08). The publisher itself arrives on Day 7; the table exists now so that the
 * transactional services written on Day 3-4 can write to it from the start.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String type;

    /** Stored as jsonb so events stay queryable for audit without a schema migration per type. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", length = 128)
    private String correlationId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboxEvent() {
        // for JPA
    }

    public static OutboxEvent pending(String aggregateType, UUID aggregateId, String type,
                                      String payload, String correlationId, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.type = type;
        event.payload = payload;
        event.correlationId = correlationId;
        event.attempts = 0;
        event.createdAt = now;
        event.publishedAt = null;
        event.nextAttemptAt = now;
        event.lastError = null;
        return event;
    }

    /** Called only after the broker has acknowledged the message. */
    public void markPublished(Instant now) {
        this.publishedAt = now;
        this.lastError = null;
    }

    public void recordFailedAttempt(Instant now, Duration retryDelay, String failure) {
        this.attempts++;
        this.nextAttemptAt = now.plus(retryDelay);
        String detail = failure == null || failure.isBlank()
                ? "RabbitMQ publish failed"
                : failure;
        this.lastError = detail.substring(0, Math.min(detail.length(), 1000));
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
