package com.ticketsystem.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketsystem.reservation.application.RequestFingerprint;
import com.ticketsystem.reservation.application.ReserveTicketsCommand;
import com.ticketsystem.reservation.application.ReserveTicketsResult;
import com.ticketsystem.reservation.application.ReserveTicketsService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Proves readiness follows the authoritative database while liveness remains independent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.connection-timeout=500",
        "spring.datasource.hikari.validation-timeout=250",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout TO 1000; SET lock_timeout TO 500",
        "spring.datasource.hikari.data-source-properties.socketTimeout=1",
        "ticketing.database.statement-timeout=1s",
        "ticketing.database.lock-timeout=500ms",
        "logging.level.org.springframework.boot.jdbc.health.DataSourceHealthIndicator=OFF"
})
@ActiveProfiles("test")
class HealthProbeRecoveryIT {

    private static final int POSTGRES_HOST_PORT = findAvailableLoopbackPort();
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("ticketsystem_health")
                    .withUsername("ticketsystem")
                    .withPassword("ticketsystem");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    static {
        // Docker assigns a new random host port when a container is restarted. A stable
        // loopback-only binding keeps the application's JDBC URL valid for this restart drill.
        POSTGRES.setPortBindings(List.of("127.0.0.1:" + POSTGRES_HOST_PORT + ":5432"));
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("database outage removes readiness but not liveness and recovers automatically")
    void probesDistinguishTrafficSafetyFromProcessLife() {
        assertProbe("/readyz", 200, "UP");
        assertProbe("/livez", 200, "UP");

        String containerId = POSTGRES.getContainerId();
        POSTGRES.getDockerClient().pauseContainerCmd(containerId).exec();
        try {
            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(200))
                    .ignoreExceptions()
                    .untilAsserted(() -> assertProbe("/readyz", 503, "DOWN"));

            assertProbe("/livez", 200, "UP");
        } finally {
            POSTGRES.getDockerClient().unpauseContainerCmd(containerId).exec();
        }

        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .untilAsserted(() -> assertProbe("/readyz", 200, "UP"));
    }

    @Test
    @DisplayName("a PostgreSQL restart preserves committed state and idempotent replay")
    void databaseRestartPreservesBookingAndDuplicateIdentity() {
        UUID eventId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID userId = UUID.randomUUID();
        String key = "database-restart-" + UUID.randomUUID();
        ReserveTicketsCommand command = new ReserveTicketsCommand(
                eventId, userId, 2, key, RequestFingerprint.of(eventId, userId, 2),
                "day11-database-restart");

        ReserveTicketsResult committed = reserveTicketsService.reserveWithResult(command);
        assertThat(committed.replayed()).isFalse();

        POSTGRES.getDockerClient().restartContainerCmd(POSTGRES.getContainerId())
                .withTimeout(10)
                .exec();

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .untilAsserted(() -> assertProbe("/readyz", 200, "UP"));

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    ReserveTicketsResult replay = reserveTicketsService.reserveWithResult(command);
                    assertThat(replay.replayed()).isTrue();
                    assertThat(replay.reservation().getId())
                            .isEqualTo(committed.reservation().getId());
                });

        Map<String, Object> state = jdbc.queryForMap("""
                SELECT i.available, i.held, i.sold,
                       count(r.id) AS reservation_rows,
                       i.available + i.held + i.sold - i.total AS conservation_drift
                FROM ticket_inventory i
                LEFT JOIN reservation r ON r.event_id = i.event_id
                WHERE i.event_id = ?::uuid
                GROUP BY i.total, i.available, i.held, i.sold
                """, eventId);
        assertThat(state)
                .containsEntry("available", 98)
                .containsEntry("held", 2)
                .containsEntry("sold", 0)
                .containsEntry("reservation_rows", 1L)
                .containsEntry("conservation_drift", 0);
    }

    private void assertProbe(String path, int expectedStatus, String expectedState) {
        try {
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(expectedStatus);
            assertThat(response.body()).contains("\"status\":\"" + expectedState + "\"");
        } catch (Exception failure) {
            throw new AssertionError("Probe call failed: " + path, failure);
        }
    }

    private static int findAvailableLoopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("Could not allocate the database restart test port", failure);
        }
    }
}
