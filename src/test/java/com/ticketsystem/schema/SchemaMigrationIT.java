package com.ticketsystem.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The Day 2 acceptance gate.
 *
 * <p>Proves two things: the database can be rebuilt from zero by Flyway alone, and the
 * constraints - not the application - are what reject invalid counters and duplicate
 * idempotency keys. Every test here writes raw SQL on purpose: if the rules only held when
 * accessed through JPA, they would not be rules.
 */
class SchemaMigrationIT extends AbstractPostgresIT {

    @Test
    @DisplayName("Flyway builds the whole schema from zero and records every migration")
    void schemaIsBuiltFromZero() {
        List<Map<String, Object>> applied = jdbc().queryForList(
                "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        assertThat(applied).extracting(row -> row.get("version"))
                .contains("1", "2", "3", "4", "5", "6", "7", "9001");

        List<String> tables = jdbc().queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                        + "ORDER BY table_name",
                String.class);

        assertThat(tables).contains("event", "ticket_inventory", "reservation",
                "ticket_order", "outbox_event", "processed_event");
    }

    @Test
    @DisplayName("the seed creates a hot event with exactly 100 tickets, all available")
    void seedCreatesHotEventWithExactlyOneHundredTickets() {
        Map<String, Object> inventory = jdbc().queryForMap(
                "SELECT total, available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);

        assertThat(inventory.get("total")).isEqualTo(100);
        assertThat(inventory.get("available")).isEqualTo(100);
        assertThat(inventory.get("held")).isEqualTo(0);
        assertThat(inventory.get("sold")).isEqualTo(0);

        String status = jdbc().queryForObject(
                "SELECT status FROM event WHERE id = ?::uuid", String.class, HOT_EVENT);
        assertThat(status).isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("the seed also provides not-yet-on-sale and closed fixtures")
    void seedProvidesRejectionPathFixtures() {
        assertThat(jdbc().queryForObject(
                "SELECT sale_starts_at > now() FROM event WHERE id = ?::uuid",
                Boolean.class, SCHEDULED_EVENT)).isTrue();
        assertThat(jdbc().queryForObject(
                "SELECT sale_ends_at < now() FROM event WHERE id = ?::uuid",
                Boolean.class, CLOSED_EVENT)).isTrue();
    }

    // --- Invariant I1: available >= 0 -------------------------------------------------

    @Test
    @DisplayName("a negative available counter is rejected by the database (I1)")
    void negativeAvailableIsRejected() {
        assertThatThrownBy(() -> jdbc().update(
                "UPDATE ticket_inventory SET available = -1, total = 99 WHERE event_id = ?::uuid",
                HOT_EVENT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inventory_available_non_negative");

        assertInventoryUntouched();
    }

    @Test
    @DisplayName("negative held and sold counters are rejected too")
    void negativeHeldAndSoldAreRejected() {
        assertThatThrownBy(() -> jdbc().update(
                "UPDATE ticket_inventory SET held = -1, total = 99 WHERE event_id = ?::uuid", HOT_EVENT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inventory_held_non_negative");

        assertThatThrownBy(() -> jdbc().update(
                "UPDATE ticket_inventory SET sold = -5, total = 95 WHERE event_id = ?::uuid", HOT_EVENT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inventory_sold_non_negative");

        assertInventoryUntouched();
    }

    // --- Invariant I2: available + held + sold = total --------------------------------

    @Test
    @DisplayName("breaking inventory conservation is rejected, even with non-negative counters (I2)")
    void conservationViolationIsRejected() {
        // Every counter is individually legal; only the sum is wrong. This is the case a
        // naive `counters >= 0` check would miss - the oversell that leaks a ticket.
        assertThatThrownBy(() -> jdbc().update(
                "UPDATE ticket_inventory SET available = 99, held = 5, sold = 0 WHERE event_id = ?::uuid",
                HOT_EVENT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_inventory_conservation");

        assertInventoryUntouched();
    }

    @Test
    @DisplayName("a legal counter movement that conserves the total is accepted")
    void legalCounterMovementIsAccepted() {
        jdbc().update("UPDATE ticket_inventory SET available = 90, held = 10 WHERE event_id = ?::uuid",
                HOT_EVENT);
        try {
            Integer drift = jdbc().queryForObject(
                    "SELECT conservation_drift FROM v_inventory_reconciliation WHERE event_id = ?::uuid",
                    Integer.class, HOT_EVENT);
            assertThat(drift).isZero();
        } finally {
            jdbc().update("UPDATE ticket_inventory SET available = 100, held = 0 WHERE event_id = ?::uuid",
                    HOT_EVENT);
        }
    }

    @Test
    @DisplayName("total must be positive")
    void zeroTotalIsRejected() {
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO ticket_inventory (event_id, total, available, held, sold) "
                        + "VALUES (?::uuid, 0, 0, 0, 0)", CLOSED_EVENT))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void assertInventoryUntouched() {
        Map<String, Object> inventory = jdbc().queryForMap(
                "SELECT total, available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);
        assertThat(inventory.get("total")).isEqualTo(100);
        assertThat(inventory.get("available")).isEqualTo(100);
    }
}
