package com.ticketsystem.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the indexes are actually used by the queries they were created for.
 *
 * <p>An index that exists but is never chosen by the planner is worse than no index: it
 * costs write throughput and buys nothing. These assertions are deliberately made now,
 * while the schema is still cheap to change, rather than being discovered during the Day 9
 * benchmark.
 *
 * <p>The table is seeded with enough rows to make a sequential scan unattractive; on a tiny
 * table PostgreSQL will correctly prefer a seq scan no matter what indexes exist.
 */
class SchemaQueryPlanIT extends AbstractPostgresIT {

    private static final String FINGERPRINT = "c".repeat(64);
    private static final UUID CAPPED_USER = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");

    @BeforeEach
    void seedEnoughRowsToMakePlannerChooseIndexes() {
        Integer existing = jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class);
        if (existing != null && existing >= 5_000) {
            return;
        }
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");
        // 5,000 terminal rows (the realistic long-term shape: history dominates live holds)
        // plus a handful of live ones.
        jdbc().update("""
                INSERT INTO reservation (id, event_id, user_id, qty, status, idempotency_key,
                                         request_fingerprint, expires_at, terminated_at)
                SELECT gen_random_uuid(), ?::uuid, gen_random_uuid(), 1, 'CANCELLED',
                       'seed-' || g, ?, now() - INTERVAL '1 day', now() - INTERVAL '1 day'
                FROM generate_series(1, 5000) g
                """, HOT_EVENT, FINGERPRINT);
        jdbc().update("""
                INSERT INTO reservation (id, event_id, user_id, qty, status, idempotency_key,
                                         request_fingerprint, expires_at)
                SELECT gen_random_uuid(), ?::uuid, ?::uuid, 1, 'PENDING',
                       'live-' || g, ?, now() - INTERVAL '1 minute'
                FROM generate_series(1, 20) g
                """, HOT_EVENT, CAPPED_USER, FINGERPRINT);
        jdbc().execute("ANALYZE reservation");
    }

    private String explain(String sql, Object... args) {
        List<String> lines = jdbc().queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql, String.class, args);
        return String.join("\n", lines);
    }

    @Test
    @DisplayName("projected catalog pages use covering indexes without a separate sort")
    void catalogPagesUseCoveringIndexes() {
        jdbc().update("""
                INSERT INTO event (id, name, venue, starts_at, sale_starts_at, sale_ends_at,
                                   status, price_minor, currency)
                SELECT gen_random_uuid(), 'Catalog event ' || g, 'Load venue',
                       now() + INTERVAL '30 days', now() - g * INTERVAL '1 second',
                       now() + INTERVAL '29 days',
                       CASE WHEN g % 2 = 0 THEN 'ON_SALE' ELSE 'SCHEDULED' END,
                       5000, 'USD'
                FROM generate_series(1, 5000) g
                """);
        jdbc().execute("ANALYZE event");

        String projection = """
                SELECT id, name, venue, starts_at, sale_starts_at, sale_ends_at,
                       status, price_minor, currency
                FROM event
                """;
        String filtered = explain(projection + """
                WHERE status = 'ON_SALE'
                ORDER BY sale_starts_at DESC, id DESC
                LIMIT 20 OFFSET 40
                """);
        String unfiltered = explain(projection + """
                ORDER BY sale_starts_at DESC, id DESC
                LIMIT 20 OFFSET 40
                """);

        assertThat(filtered)
                .contains("Index Only Scan using ix_event_status_catalog_page")
                .doesNotContain("Sort  ");
        assertThat(unfiltered)
                .contains("Index Only Scan using ix_event_catalog_page")
                .doesNotContain("Sort  ");
    }

    @Test
    @DisplayName("idempotency lookup uses the unique index, not a scan (I3)")
    void idempotencyLookupUsesUniqueIndex() {
        String plan = explain("SELECT * FROM reservation WHERE idempotency_key = ?", "live-1");

        assertThat(plan).contains("uq_reservation_idempotency_key");
        assertThat(plan).doesNotContain("Seq Scan");
    }

    @Test
    @DisplayName("the expiry worker's claim query uses the partial index (F-05)")
    void expiryClaimUsesPartialIndex() {
        String plan = explain("""
                SELECT id FROM reservation
                WHERE status = 'PENDING' AND expires_at <= now()
                ORDER BY expires_at
                LIMIT 200
                """);

        assertThat(plan)
                .as("a literal status is what lets the planner match the partial index")
                .contains("ix_reservation_pending_expiry");
        assertThat(plan).doesNotContain("Seq Scan");
    }

    @Test
    @DisplayName("the per-user cap check is an index-only scan, keeping the critical section short (I6)")
    void userCapCheckIsIndexOnly() {
        String plan = explain("""
                SELECT COALESCE(SUM(qty), 0) FROM reservation
                WHERE user_id = ?::uuid AND event_id = ?::uuid
                  AND status = ANY (ARRAY['PENDING', 'CONFIRMED'])
                """, CAPPED_USER, HOT_EVENT);

        assertThat(plan).contains("ix_reservation_user_event_status");
        assertThat(plan)
                .as("INCLUDE (qty) should let this run without touching the heap")
                .contains("Index Only Scan");
    }

    @Test
    @DisplayName("the ready outbox query uses the partial retry-schedule index")
    void outboxBacklogUsesPartialIndex() {
        jdbc().update("""
                INSERT INTO outbox_event (id, aggregate_type, aggregate_id, type, payload, created_at,
                                          published_at)
                SELECT gen_random_uuid(), 'reservation', gen_random_uuid(), 'reservation.created',
                       '{}'::jsonb, now() - INTERVAL '1 hour', now()
                FROM generate_series(1, 5000)
                """);
        jdbc().execute("ANALYZE outbox_event");

        String plan = explain("""
                SELECT * FROM outbox_event
                WHERE published_at IS NULL
                  AND next_attempt_at <= now()
                ORDER BY created_at, id
                LIMIT 100
                """);

        assertThat(plan).contains("ix_outbox_ready");
        assertThat(plan).doesNotContain("Seq Scan");
        jdbc().update("DELETE FROM outbox_event");
    }

    @Test
    @DisplayName("the reconciliation view returns zero drift for a clean database")
    void reconciliationIsCleanWhenCountersMatchTheLedger() {
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");

        List<java.util.Map<String, Object>> drift = jdbc().queryForList("""
                SELECT event_id, conservation_drift, held_drift, sold_drift
                FROM v_inventory_reconciliation
                WHERE conservation_drift <> 0 OR held_drift <> 0 OR sold_drift <> 0
                """);

        assertThat(drift).as("the C1 release gate query must return zero rows").isEmpty();
    }
}
