package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.catalog.domain.SaleEndedException;
import com.ticketsystem.catalog.domain.SaleNotStartedException;
import com.ticketsystem.inventory.domain.InsufficientInventoryException;
import com.ticketsystem.reservation.domain.IdempotencyKeyConflictException;
import com.ticketsystem.reservation.domain.InvalidQuantityException;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationStatus;
import com.ticketsystem.reservation.domain.UserLimitExceededException;
import com.ticketsystem.schema.AbstractPostgresIT;
import com.ticketsystem.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Single-threaded behaviour of the reserve transaction: the happy path and every rejection. */
class ReserveTicketsServiceIT extends AbstractPostgresIT {

    private static final UUID HOT = UUID.fromString(HOT_EVENT);
    private static final UUID SCHEDULED = UUID.fromString(SCHEDULED_EVENT);
    private static final UUID CLOSED = UUID.fromString(CLOSED_EVENT);

    @Autowired
    private ReserveTicketsService service;

    @BeforeEach
    void reset() {
        jdbc().update("DELETE FROM outbox_event");
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");
        jdbc().update("UPDATE ticket_inventory SET total = 100, available = 100, held = 0, sold = 0 "
                + "WHERE event_id = ?::uuid", HOT_EVENT);
    }

    private ReserveTicketsCommand command(UUID eventId, UUID userId, int quantity) {
        String key = "k-" + UUID.randomUUID();
        return new ReserveTicketsCommand(eventId, userId, quantity, key,
                RequestFingerprint.of(eventId, userId, quantity), "req-1");
    }

    @Test
    @DisplayName("a valid request creates a PENDING hold and moves available to held")
    void happyPath() {
        UUID user = UUID.randomUUID();

        Reservation reservation = service.reserve(command(HOT, user, 2));

        assertThat(reservation.getId()).isNotNull();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(reservation.getQty()).isEqualTo(2);
        assertThat(reservation.getTerminatedAt()).isNull();

        var inventory = jdbc().queryForMap(
                "SELECT available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid", HOT_EVENT);
        assertThat(inventory.get("available")).isEqualTo(98);
        assertThat(inventory.get("held")).isEqualTo(2);
        assertThat(inventory.get("sold")).isEqualTo(0);
    }

    @Test
    @DisplayName("the hold deadline comes from database time, not the JVM clock (F-18)")
    void deadlineIsDerivedFromDatabaseTime() {
        Reservation reservation = service.reserve(command(HOT, UUID.randomUUID(), 1));

        Instant databaseNow = jdbc().queryForObject("SELECT now()", Instant.class);
        Duration remaining = Duration.between(databaseNow, reservation.getExpiresAt());

        assertThat(remaining)
                .as("configured TTL is 3 minutes")
                .isBetween(Duration.ofSeconds(150), Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("an accepted hold writes its outbox event in the same transaction")
    void outboxEventIsWrittenTransactionally() {
        Reservation reservation = service.reserve(command(HOT, UUID.randomUUID(), 1));

        var outbox = jdbc().queryForMap(
                "SELECT aggregate_type, aggregate_id, type, published_at FROM outbox_event");
        assertThat(outbox.get("aggregate_type")).isEqualTo("reservation");
        assertThat(outbox.get("aggregate_id")).hasToString(reservation.getId().toString());
        assertThat(outbox.get("type")).isEqualTo("reservation.created");
        assertThat(outbox.get("published_at")).as("nothing is published inside the transaction").isNull();
    }
    @Test
    @DisplayName("SOLD_OUT once inventory is exhausted, with counters untouched")
    void soldOut() {
        jdbc().update("UPDATE ticket_inventory SET available = 0, held = 100 WHERE event_id = ?::uuid",
                HOT_EVENT);

        assertThatThrownBy(() -> service.reserve(command(HOT, UUID.randomUUID(), 1)))
                .isInstanceOf(InsufficientInventoryException.class)
                .extracting(e -> ((InsufficientInventoryException) e).errorCode())
                .isEqualTo(ErrorCode.SOLD_OUT);

        var inventory = jdbc().queryForMap(
                "SELECT available, held FROM ticket_inventory WHERE event_id = ?::uuid", HOT_EVENT);
        assertThat(inventory.get("available")).isEqualTo(0);
        assertThat(inventory.get("held")).isEqualTo(100);
    }

    @Test
    @DisplayName("a rejected request leaves no reservation row and no outbox row behind")
    void rejectionRollsBackTheHold() {
        jdbc().update("UPDATE ticket_inventory SET available = 0, held = 100 WHERE event_id = ?::uuid",
                HOT_EVENT);

        assertThatThrownBy(() -> service.reserve(command(HOT, UUID.randomUUID(), 1)))
                .isInstanceOf(InsufficientInventoryException.class);

        Integer rows = jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class);
        assertThat(rows).as("the hold inserted before the lock must roll back with the transaction")
                .isZero();
        Integer outbox = jdbc().queryForObject("SELECT count(*) FROM outbox_event", Integer.class);
        assertThat(outbox).isZero();
    }

    @Test
    @DisplayName("SALE_NOT_STARTED before the sale window opens")
    void saleNotStarted() {
        assertThatThrownBy(() -> service.reserve(command(SCHEDULED, UUID.randomUUID(), 1)))
                .isInstanceOf(SaleNotStartedException.class);
    }

    @Test
    @DisplayName("SALE_ENDED after the sale window closes")
    void saleEnded() {
        assertThatThrownBy(() -> service.reserve(command(CLOSED, UUID.randomUUID(), 1)))
                .isInstanceOf(SaleEndedException.class);
    }

    @Test
    @DisplayName("INVALID_QUANTITY outside the configured bounds, checked before any database work")
    void invalidQuantity() {
        assertThatThrownBy(() -> service.reserve(command(HOT, UUID.randomUUID(), 0)))
                .isInstanceOf(InvalidQuantityException.class);
        assertThatThrownBy(() -> service.reserve(command(HOT, UUID.randomUUID(), 5)))
                .isInstanceOf(InvalidQuantityException.class);
        assertThatThrownBy(() -> service.reserve(command(HOT, UUID.randomUUID(), -2)))
                .isInstanceOf(InvalidQuantityException.class);
    }

    @Test
    @DisplayName("EVENT_NOT_FOUND for an unknown event")
    void eventNotFound() {
        assertThatThrownBy(() -> service.reserve(command(UUID.randomUUID(), UUID.randomUUID(), 1)))
                .isInstanceOf(EventNotFoundException.class);
    }

    @Test
    @DisplayName("USER_LIMIT_EXCEEDED once the per-event cap is reached, even with tickets left")
    void userLimitExceeded() {
        UUID user = UUID.randomUUID();
        service.reserve(command(HOT, user, 4));

        assertThatThrownBy(() -> service.reserve(command(HOT, user, 1)))
                .isInstanceOf(UserLimitExceededException.class);

        var inventory = jdbc().queryForMap(
                "SELECT available, held FROM ticket_inventory WHERE event_id = ?::uuid", HOT_EVENT);
        assertThat(inventory.get("available")).as("96 tickets remain; the cap is what refused")
                .isEqualTo(96);
        assertThat(inventory.get("held")).isEqualTo(4);
    }

    @Test
    @DisplayName("a cancelled hold no longer counts against the user's cap")
    void terminatedHoldsDoNotConsumeTheCap() {
        UUID user = UUID.randomUUID();
        Reservation first = service.reserve(command(HOT, user, 4));
        jdbc().update("UPDATE reservation SET status = 'CANCELLED', terminated_at = now() "
                + "WHERE id = ?::uuid", first.getId());

        Reservation second = service.reserve(command(HOT, user, 3));

        assertThat(second.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    @DisplayName("replay recomputes the fingerprint instead of trusting a caller-supplied hash")
    void callerCannotForgeAnIdempotentReplay() {
        UUID user = UUID.randomUUID();
        String key = "fingerprint-" + UUID.randomUUID();
        String originalFingerprint = RequestFingerprint.of(HOT, user, 1);

        service.reserve(new ReserveTicketsCommand(
                HOT, user, 1, key, originalFingerprint, null));

        ReserveTicketsCommand forged = new ReserveTicketsCommand(
                HOT, user, 2, key, originalFingerprint, null);
        assertThatThrownBy(() -> service.reserve(forged))
                .isInstanceOf(IdempotencyKeyConflictException.class);

        var inventory = jdbc().queryForMap(
                "SELECT available, held FROM ticket_inventory WHERE event_id = ?::uuid", HOT_EVENT);
        assertThat(inventory.get("available")).isEqualTo(99);
        assertThat(inventory.get("held")).isEqualTo(1);
    }
}
