package com.ticketsystem.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * Constraints that make illegal reservation and order states impossible to persist,
 * exercised with raw SQL so they hold for any writer, not just this application.
 */
class ReservationConstraintsIT extends AbstractPostgresIT {

    private static final String FINGERPRINT = "b".repeat(64);

    @AfterEach
    void cleanUp() {
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");
    }

    private UUID insertPendingHold(String idempotencyKey, int qty) {
        UUID id = UUID.randomUUID();
        jdbc().update("""
                INSERT INTO reservation (id, event_id, user_id, qty, status, idempotency_key,
                                         request_fingerprint, expires_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'PENDING', ?, ?, now() + INTERVAL '3 minutes')
                """, id, HOT_EVENT, UUID.randomUUID(), qty, idempotencyKey, FINGERPRINT);
        return id;
    }

    // --- Invariant I3: one idempotency key -> one reservation -------------------------

    @Test
    @DisplayName("a duplicate idempotency key is rejected by the database, not by Java (I3)")
    void duplicateIdempotencyKeyIsRejected() {
        insertPendingHold("dup-key-1", 2);

        assertThatThrownBy(() -> insertPendingHold("dup-key-1", 2))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class)
                .hasMessageContaining("uq_reservation_idempotency_key");

        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM reservation WHERE idempotency_key = 'dup-key-1'", Integer.class);
        assertThat(count).as("exactly one hold survives a duplicate key").isEqualTo(1);
    }

    @Test
    @DisplayName("the key stays unique even after the original hold reaches a terminal state")
    void idempotencyKeyRemainsUniqueAfterTermination() {
        UUID id = insertPendingHold("dup-key-2", 1);
        jdbc().update("UPDATE reservation SET status = 'CANCELLED', terminated_at = now() "
                + "WHERE id = ?::uuid", id);

        assertThatThrownBy(() -> insertPendingHold("dup-key-2", 1))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);
    }

    // --- Quantity and status shape ----------------------------------------------------

    @Test
    @DisplayName("a non-positive quantity is rejected")
    void nonPositiveQuantityIsRejected() {
        assertThatThrownBy(() -> insertPendingHold("qty-zero", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_qty_positive");

        assertThatThrownBy(() -> insertPendingHold("qty-negative", -3))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_qty_positive");
    }

    @Test
    @DisplayName("an unknown status value is rejected")
    void unknownStatusIsRejected() {
        UUID id = insertPendingHold("status-check", 1);

        assertThatThrownBy(() -> jdbc().update(
                "UPDATE reservation SET status = 'REFUNDED', terminated_at = now() WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_status");
    }
    @Test
    @DisplayName("a terminal status without a termination timestamp is rejected")
    void halfAppliedTransitionIsRejected() {
        UUID id = insertPendingHold("half-applied", 1);

        // The exact shape of a half-applied transition: status moved, terminated_at did not.
        assertThatThrownBy(() -> jdbc().update(
                "UPDATE reservation SET status = 'CONFIRMED' WHERE id = ?::uuid", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_terminal_consistency");

        String status = jdbc().queryForObject(
                "SELECT status FROM reservation WHERE id = ?::uuid", String.class, id);
        assertThat(status).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a PENDING hold carrying a termination timestamp is rejected")
    void pendingWithTerminationTimestampIsRejected() {
        assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO reservation (id, event_id, user_id, qty, status, idempotency_key,
                                         request_fingerprint, expires_at, terminated_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'PENDING', 'pending-terminated', ?,
                        now() + INTERVAL '3 minutes', now())
                """, UUID.randomUUID(), HOT_EVENT, UUID.randomUUID(), FINGERPRINT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservation_terminal_consistency");
    }

    @Test
    @DisplayName("a complete transition - status and timestamp together - is accepted")
    void completeTransitionIsAccepted() {
        UUID id = insertPendingHold("complete-transition", 1);

        assertThatCode(() -> jdbc().update(
                "UPDATE reservation SET status = 'CONFIRMED', terminated_at = now() WHERE id = ?::uuid", id))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a reservation for an unknown event is rejected by the foreign key")
    void unknownEventIsRejected() {
        assertThatThrownBy(() -> jdbc().update("""
                INSERT INTO reservation (id, event_id, user_id, qty, status, idempotency_key,
                                         request_fingerprint, expires_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, 1, 'PENDING', 'orphan', ?,
                        now() + INTERVAL '3 minutes')
                """, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), FINGERPRINT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_reservation_event");
    }

    // --- Invariant I5: one order per reservation --------------------------------------

    @Test
    @DisplayName("a second order for the same reservation is rejected (I5)")
    void oneOrderPerReservation() {
        UUID reservationId = insertPendingHold("order-once", 2);
        jdbc().update("UPDATE reservation SET status = 'CONFIRMED', terminated_at = now() "
                + "WHERE id = ?::uuid", reservationId);

        insertOrder(reservationId);

        assertThatThrownBy(() -> insertOrder(reservationId))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class)
                .hasMessageContaining("uq_ticket_order_reservation");

        Integer orders = jdbc().queryForObject(
                "SELECT count(*) FROM ticket_order WHERE reservation_id = ?::uuid",
                Integer.class, reservationId);
        assertThat(orders).as("a retried confirmation cannot double-sell").isEqualTo(1);
    }

    private void insertOrder(UUID reservationId) {
        jdbc().update("""
                INSERT INTO ticket_order (id, reservation_id, user_id, status, amount_minor, currency)
                VALUES (?::uuid, ?::uuid, ?::uuid, 'PAID', 9000, 'EUR')
                """, UUID.randomUUID(), reservationId, UUID.randomUUID());
    }

    // --- Reconciliation view -----------------------------------------------------------

    @Test
    @DisplayName("the reconciliation view detects counters that disagree with the ledger")
    void reconciliationViewDetectsLedgerDrift() {
        insertPendingHold("ledger-drift", 4);
        // Counters left untouched on purpose: internally consistent, but no longer matching
        // the reservation ledger. This is the bug class CHECK constraints cannot catch.

        Integer heldDrift = jdbc().queryForObject(
                "SELECT held_drift FROM v_inventory_reconciliation WHERE event_id = ?::uuid",
                Integer.class, HOT_EVENT);

        assertThat(heldDrift).as("inventory says 0 held, the ledger says 4").isEqualTo(-4);
    }
}
