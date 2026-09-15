package com.ticketsystem.catalog.domain;

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
 * Catalog entry for a sellable event.
 *
 * <p>Deliberately has <em>no</em> JPA association to reservations or inventory. Loading an
 * event must never be able to drag a collection of holds into memory, and cross-module
 * access goes through service interfaces rather than object graphs
 * (see {@code docs/architecture.md} section 3.1). Other aggregates reference an event by
 * its {@link UUID}, which also means no association can produce an accidental N+1.
 */
@Entity
@Table(name = "event")
public class Event {

    /**
     * Time-ordered UUID (version 7). Random v4 keys scatter B-tree inserts across the whole
     * index; time-ordered keys append, which matters on the high-insert tables and costs
     * nothing here.
     */
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 200)
    private String venue;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "sale_starts_at", nullable = false)
    private Instant saleStartsAt;

    @Column(name = "sale_ends_at")
    private Instant saleEndsAt;

    /** STRING, never ORDINAL: reordering the enum must not silently reinterpret stored rows. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Column(name = "price_minor", nullable = false)
    private long priceMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Event() {
        // for JPA
    }

    public static Event create(String name, String venue, Instant startsAt, Instant saleStartsAt,
                               Instant saleEndsAt, long priceMinor, String currency, Instant now) {
        Event event = new Event();
        event.name = requireText(name, "name", 200);
        event.venue = requireText(venue, "venue", 200);
        event.startsAt = startsAt;
        event.saleStartsAt = requireSaleStartsAt(saleStartsAt);
        event.saleEndsAt = requireSaleEndsAt(event.saleStartsAt, saleEndsAt);
        event.status = EventStatus.SCHEDULED;
        event.priceMinor = requirePrice(priceMinor);
        event.currency = requireCurrency(currency);
        event.createdAt = requireNow(now);
        event.updatedAt = now;
        return event;
    }

    /** Applies an admin metadata patch while preserving the database constraints in Java. */
    public void update(String name, String venue, Instant startsAt, Instant saleStartsAt,
                       Instant saleEndsAt, EventStatus status, Long priceMinor, String currency,
                       Instant now) {
        String nextName = name == null ? this.name : requireText(name, "name", 200);
        String nextVenue = venue == null ? this.venue : requireText(venue, "venue", 200);
        Instant nextSaleStartsAt = saleStartsAt == null
                ? this.saleStartsAt : requireSaleStartsAt(saleStartsAt);
        Instant nextSaleEndsAt = saleEndsAt == null ? this.saleEndsAt : saleEndsAt;

        this.name = nextName;
        this.venue = nextVenue;
        this.startsAt = startsAt == null ? this.startsAt : startsAt;
        this.saleStartsAt = nextSaleStartsAt;
        this.saleEndsAt = requireSaleEndsAt(nextSaleStartsAt, nextSaleEndsAt);
        this.status = status == null ? this.status : status;
        this.priceMinor = priceMinor == null ? this.priceMinor : requirePrice(priceMinor);
        this.currency = currency == null ? this.currency : requireCurrency(currency);
        this.updatedAt = requireNow(now);
    }

    /**
     * Whether the sale window is open at {@code now}.
     *
     * <p>{@code now} must be database time, not application time: with several replicas a
     * skewed container clock would otherwise open or close a sale inconsistently (F-18).
     */
    public boolean isSaleOpenAt(Instant now) {
        return status.allowsReservation()
                && !now.isBefore(saleStartsAt)
                && (saleEndsAt == null || now.isBefore(saleEndsAt));
    }

    public boolean isSaleNotStartedAt(Instant now) {
        return now.isBefore(saleStartsAt);
    }

    public boolean isSaleEndedAt(Instant now) {
        return saleEndsAt != null && !now.isBefore(saleEndsAt);
    }

    public void markSoldOut(Instant now) {
        this.status = EventStatus.SOLD_OUT;
        this.updatedAt = now;
    }

    public void openSale(Instant now) {
        this.status = EventStatus.ON_SALE;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new InvalidEventException(
                    "%s must contain 1-%d characters".formatted(field, maxLength));
        }
        return value;
    }

    private static Instant requireSaleStartsAt(Instant value) {
        if (value == null) {
            throw new InvalidEventException("saleStartsAt is required");
        }
        return value;
    }

    private static Instant requireSaleEndsAt(Instant saleStartsAt, Instant saleEndsAt) {
        if (saleEndsAt != null && !saleEndsAt.isAfter(saleStartsAt)) {
            throw new InvalidEventException("saleEndsAt must be after saleStartsAt");
        }
        return saleEndsAt;
    }

    private static long requirePrice(long value) {
        if (value < 0) {
            throw new InvalidEventException("priceMinor must be non-negative");
        }
        return value;
    }

    private static String requireCurrency(String value) {
        if (value == null || !value.matches("[A-Z]{3}")) {
            throw new InvalidEventException("currency must be exactly three uppercase letters");
        }
        return value;
    }

    private static Instant requireNow(Instant value) {
        if (value == null) {
            throw new InvalidEventException("database time is required");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVenue() {
        return venue;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getSaleStartsAt() {
        return saleStartsAt;
    }

    public Instant getSaleEndsAt() {
        return saleEndsAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public long getPriceMinor() {
        return priceMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
