package com.ticketsystem.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketsystem.reservation.application.ReserveTicketsCommand;
import com.ticketsystem.reservation.application.ReserveTicketsResult;
import com.ticketsystem.reservation.application.ReserveTicketsService;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Real PostgreSQL drills for an abrupt replica loss and a slow/locked database. */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout TO 1000; SET lock_timeout TO 250",
        "ticketing.database.statement-timeout=1s",
        "ticketing.database.lock-timeout=250ms"
})
class FailureRecoveryIT extends AbstractPostgresIT {

    private static final UUID USER =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID EVENT = UUID.fromString(HOT_EVENT);

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("an abrupt replica disconnect rolls back its transaction and releases its lock")
    void appCrashRollsBackAndWaitingReplicaRecovers() throws Exception {
        try (Connection dyingReplica = rawConnection();
             ExecutorService survivor = Executors.newVirtualThreadPerTaskExecutor()) {
            dyingReplica.setAutoCommit(false);
            try (PreparedStatement update = dyingReplica.prepareStatement("""
                    UPDATE ticket_inventory SET available = 99, held = 1
                    WHERE event_id = ?::uuid
                    """)) {
                update.setString(1, HOT_EVENT);
                assertThat(update.executeUpdate()).isOne();
            }

            Future<ReserveTicketsResult> waitingRequest = survivor.submit(() ->
                    reserveTicketsService.reserveWithResult(command("survivor-after-crash")));
            Thread.sleep(Duration.ofMillis(100));

            // Closing a process socket is what PostgreSQL observes when a replica is killed.
            // abort() gives that behavior without asking the JDBC client to roll back first.
            dyingReplica.abort(Runnable::run);

            ReserveTicketsResult result = waitingRequest.get(3, TimeUnit.SECONDS);
            assertThat(result.replayed()).isFalse();
            assertThat(result.reservation().getQty()).isOne();
        }

        assertState(99, 1, 0, 1);
    }

    @Test
    @DisplayName("a slow inventory lock fails as 503, then the same idempotent request recovers")
    void slowDatabaseFailsFastWithoutPartialInventoryMovement() throws Exception {
        String key = "slow-db-" + UUID.randomUUID();
        try (Connection blocker = rawConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement("""
                    SELECT event_id FROM ticket_inventory
                    WHERE event_id = ?::uuid FOR UPDATE
                    """)) {
                lock.setString(1, HOT_EVENT);
                lock.executeQuery();
            }

            long started = System.nanoTime();
            mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                            .with(bearer(USER.toString(), "USER"))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":1}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(2));
        }

        assertState(100, 0, 0, 0);

        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .with(bearer(USER.toString(), "USER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isCreated());

        assertState(99, 1, 0, 1);
    }

    private Connection rawConnection() throws Exception {
        return DriverManager.getConnection(
                postgres().getJdbcUrl(), postgres().getUsername(), postgres().getPassword());
    }

    private ReserveTicketsCommand command(String key) {
        return new ReserveTicketsCommand(EVENT, USER, 1, key, "ignored", "day8-crash-drill");
    }

    private void assertState(int available, int held, int sold, int reservations) {
        Map<String, Object> inventory = jdbc().queryForMap("""
                SELECT available, held, sold,
                       available + held + sold - total AS conservation_drift
                FROM ticket_inventory WHERE event_id = ?::uuid
                """, HOT_EVENT);
        assertThat(inventory)
                .containsEntry("available", available)
                .containsEntry("held", held)
                .containsEntry("sold", sold)
                .containsEntry("conservation_drift", 0);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM reservation", Integer.class)).isEqualTo(reservations);
    }
}
