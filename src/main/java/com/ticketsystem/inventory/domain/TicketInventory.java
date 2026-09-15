package com.ticketsystem.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative ticket counters for one event - the single hot row the whole design turns
 * on (see {@code docs/adr/ADR-001-postgresql-inventory-authority.md}).
 *
 * <p>Lifecycle counters are moved through the methods below after an explicit row lock.
 * The Day 9 reserve path may instead use one conditional native UPDATE, which acquires the
 * same PostgreSQL row lock internally. In both cases the {@code CHECK} constraints in
 * {@code V2__create_ticket_inventory.sql} remain the real backstop: if application logic is
 * ever wrong, the transaction fails to commit.
 *
 * <p>Every method takes {@code now} rather than reading a clock, so callers must supply
 * database time (F-18) and tests can be deterministic.
 */
@Entity
@Table(name = "ticket_inventory")
public class TicketInventory {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(nullable = false)
    private int total;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int held;

    @Column(nullable = false)
    private int sold;

    /**
     * Mutation counter for observability only.
     *
     * <p>Deliberately <em>not</em> a JPA {@code @Version}: both reservation strategies
     * serialize on PostgreSQL's row lock and do not use optimistic retry for correctness.
     */
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketInventory() {
        // for JPA
    }

    public static TicketInventory of(UUID eventId, int total, Instant now) {
        if (total <= 0) {
            throw new IllegalArgumentException("total must be positive, was " + total);
        }
        TicketInventory inventory = new TicketInventory();
        inventory.eventId = eventId;
        inventory.total = total;
        inventory.available = total;
        inventory.held = 0;
        inventory.sold = 0;
        inventory.version = 0;
        inventory.updatedAt = now;
        return inventory;
    }

    /** Reserve: available -> held. Rejects rather than going negative (I1). */
    public void hold(int qty, Instant now) {
        requirePositive(qty);
        if (available < qty) {
            throw new InsufficientInventoryException(eventId, qty, available);
        }
        available -= qty;
        held += qty;
        touch(now);
    }

    /** Cancel or expire: held -> available. Callers must ensure this runs at most once (I4). */
    public void releaseHold(int qty, Instant now) {
        requirePositive(qty);
        requireHeld(qty);
        held -= qty;
        available += qty;
        touch(now);
    }

    /** Confirm: held -> sold. Callers must ensure this runs at most once (I5). */
    public void confirmHold(int qty, Instant now) {
        requirePositive(qty);
        requireHeld(qty);
        held -= qty;
        sold += qty;
        touch(now);
    }

    /** Admin top-up. Inventory may grow; it may never shrink below what is already committed. */
    public void addTickets(int qty, Instant now) {
        requirePositive(qty);
        total += qty;
        available += qty;
        touch(now);
    }

    public boolean isSoldOut() {
        return available == 0;
    }

    /** Mirrors invariant I2. Must always be true; asserted by tests and by a CHECK constraint. */
    public boolean isConserved() {
        return available + held + sold == total;
    }

    private void requirePositive(int qty) {
        if (qty <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + qty);
        }
    }

    private void requireHeld(int qty) {
        if (held < qty) {
            throw new IllegalStateException(
                    "Cannot move %d tickets out of held; only %d are held for event %s"
                            .formatted(qty, held, eventId));
        }
    }

    private void touch(Instant now) {
        this.version++;
        this.updatedAt = now;
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getTotal() {
        return total;
    }

    public int getAvailable() {
        return available;
    }

    public int getHeld() {
        return held;
    }

    public int getSold() {
        return sold;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
