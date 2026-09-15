package com.ticketsystem.schema;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for schema tests, running against real PostgreSQL rather than H2.
 *
 * <p>H2 would not enforce the same CHECK semantics, would not have partial indexes or
 * SKIP LOCKED, and would therefore prove nothing about the behaviour that actually matters
 * here. The container is a singleton started once for the whole suite and deliberately not
 * stopped: Ryuk reaps it when the JVM exits, and reusing it keeps the suite fast.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("ticketsystem")
            .withUsername("ticketsystem")
            .withPassword("ticketsystem");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected DataSource dataSource;

    protected JdbcTemplate jdbc;

    @BeforeAll
    static void checkDockerIsAvailable() {
        // Fails loudly rather than mysteriously if the daemon is not running.
        if (!POSTGRES.isRunning()) {
            throw new IllegalStateException("PostgreSQL test container failed to start");
        }
    }

    protected JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /**
     * Restores the seeded fixture before every test.
     *
     * <p>The container is shared by the whole suite for speed, which means one test class can
     * otherwise leave inventory counters or reservation rows behind for the next - an
     * order-dependent suite that passes or fails depending on which tests ran first. Resetting
     * here, in the base class, makes every test start from the seeded state regardless of
     * execution order. JUnit runs this before any subclass {@code @BeforeEach}.
     */
    @BeforeEach
    void restoreSeededFixture() {
        jdbc().update("DELETE FROM outbox_event");
        jdbc().update("DELETE FROM processed_event");
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");
        jdbc().update("DELETE FROM ticket_inventory WHERE event_id NOT IN "
                        + "(?::uuid, ?::uuid, ?::uuid)",
                HOT_EVENT, SCHEDULED_EVENT, CLOSED_EVENT);
        jdbc().update("DELETE FROM event WHERE id NOT IN (?::uuid, ?::uuid, ?::uuid)",
                HOT_EVENT, SCHEDULED_EVENT, CLOSED_EVENT);

        jdbc().update("""
                UPDATE event SET name = 'Aurora Live - Opening Night',
                    venue = 'Riverside Arena', starts_at = now() + INTERVAL '30 days',
                    sale_starts_at = now() - INTERVAL '1 hour',
                    sale_ends_at = now() + INTERVAL '29 days', status = 'ON_SALE',
                    price_minor = 4500, currency = 'EUR', updated_at = now()
                WHERE id = ?::uuid
                """, HOT_EVENT);
        jdbc().update("""
                UPDATE event SET name = 'Aurora Live - Second Night',
                    venue = 'Riverside Arena', starts_at = now() + INTERVAL '31 days',
                    sale_starts_at = now() + INTERVAL '7 days',
                    sale_ends_at = now() + INTERVAL '30 days', status = 'SCHEDULED',
                    price_minor = 4500, currency = 'EUR', updated_at = now()
                WHERE id = ?::uuid
                """, SCHEDULED_EVENT);
        jdbc().update("""
                UPDATE event SET name = 'Midnight Sessions - Finale', venue = 'The Vault',
                    starts_at = now() + INTERVAL '2 days',
                    sale_starts_at = now() - INTERVAL '30 days',
                    sale_ends_at = now() - INTERVAL '1 day', status = 'CLOSED',
                    price_minor = 3000, currency = 'EUR', updated_at = now()
                WHERE id = ?::uuid
                """, CLOSED_EVENT);

        resetInventory(HOT_EVENT, 100);
        resetInventory(SCHEDULED_EVENT, 250);
        resetInventory(CLOSED_EVENT, 50);
    }

    /** Exposes the shared real PostgreSQL instance to failure-drill subclasses. */
    protected static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    private void resetInventory(String eventId, int total) {
        jdbc().update("UPDATE ticket_inventory SET total = ?, available = ?, held = 0, "
                        + "sold = 0, version = 0, updated_at = now() WHERE event_id = ?::uuid",
                total, total, eventId);
    }

    /** The seeded hot event: exactly 100 tickets, sale already open. */
    protected static final String HOT_EVENT = "11111111-1111-4111-8111-111111111111";
    protected static final String SCHEDULED_EVENT = "22222222-2222-4222-8222-222222222222";
    protected static final String CLOSED_EVENT = "33333333-3333-4333-8333-333333333333";
}
