package com.ticketsystem.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * The confirmation / payment record for exactly one reservation.
 *
 * <p>Named {@code ticket_order} because {@code ORDER} is a reserved SQL word. The
 * {@code UNIQUE(reservation_id)} constraint is what makes invariant I5 unbreakable: a
 * retried or redelivered confirmation cannot create a second order, so {@code sold} cannot
 * be incremented twice for one hold (F-07).
 */
@Entity
@Table(name = "ticket_order")
public class TicketOrder {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "reservation_id", nullable = false, unique = true)
    private UUID reservationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_reference", length = 128)
    private String paymentReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TicketOrder() {
        // for JPA
    }

    public static TicketOrder paid(UUID reservationId, UUID userId, long amountMinor,
                                   String currency, String paymentReference, Instant now) {
        TicketOrder order = new TicketOrder();
        order.reservationId = reservationId;
        order.userId = userId;
        order.status = OrderStatus.PAID;
        order.amountMinor = amountMinor;
        order.currency = currency;
        order.paymentReference = paymentReference;
        order.createdAt = now;
        return order;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
