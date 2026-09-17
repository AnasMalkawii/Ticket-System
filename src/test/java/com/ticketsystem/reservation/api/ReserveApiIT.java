package com.ticketsystem.reservation.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.time.Duration;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract for the reserve endpoint, checked against {@code docs/api/openapi.yaml}.
 *
 * <p>The error assertions matter most: every rejection must be a 4xx carrying a stable
 * {@code code} in {@code application/problem+json}. A business rejection arriving as a 5xx
 * would burn availability error budget for something the system got right.
 */
@AutoConfigureMockMvc
class ReserveApiIT extends AbstractPostgresIT {

    private static final String DEFAULT_USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void reset() {
        jdbc().update("DELETE FROM outbox_event");
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");
        jdbc().update("UPDATE ticket_inventory SET total = 100, available = 100, held = 0, sold = 0 "
                + "WHERE event_id = ?::uuid", HOT_EVENT);
    }

    private MockHttpServletRequestBuilder reserveAs(String eventId, String key, String userId,
                                                    int quantity) {
        return post("/api/v1/events/{eventId}/reservations", eventId)
                .header("Idempotency-Key", key)
                .with(bearer(userId, "USER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\": %d}".formatted(quantity));
    }

    private MockHttpServletRequestBuilder reserve(String eventId, int quantity) {
        return reserveAs(eventId, UUID.randomUUID().toString(), DEFAULT_USER, quantity);
    }

    private MockHttpServletRequestBuilder confirm(String reservationId, String paymentToken) {
        return post("/api/v1/reservations/{reservationId}/confirm", reservationId)
                .with(bearer(DEFAULT_USER, "USER"))
                .header("X-Request-Id", "confirm-api-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentToken\": \"%s\"}".formatted(paymentToken));
    }

    @Test
    @DisplayName("201 with a Location header and the reservation representation")
    void createsHold() throws Exception {
        mockMvc.perform(reserve(HOT_EVENT, 2))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", Matchers.startsWith("/api/v1/reservations/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.eventId").value(HOT_EVENT))
                .andExpect(jsonPath("$.expiresAt").exists());
    }

    @Test
    @DisplayName("409 SOLD_OUT as problem+json, not a 5xx")
    void soldOutIsAClientVisibleConflict() throws Exception {
        jdbc().update("UPDATE ticket_inventory SET available = 0, held = 100 WHERE event_id = ?::uuid",
                HOT_EVENT);

        mockMvc.perform(reserve(HOT_EVENT, 1))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.type").value("https://ticketsystem.dev/problems/sold-out"))
                .andExpect(jsonPath("$.title").value("Sold out"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @DisplayName("SALE_NOT_STARTED and SALE_ENDED stay distinct from SOLD_OUT")
    void saleWindowRejections() throws Exception {
        mockMvc.perform(reserve(SCHEDULED_EVENT, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_NOT_STARTED"));

        mockMvc.perform(reserve(CLOSED_EVENT, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SALE_ENDED"));
    }
    @Test
    @DisplayName("400 INVALID_QUANTITY above the configured maximum")
    void quantityAboveMaximum() throws Exception {
        mockMvc.perform(reserve(HOT_EVENT, 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR for a non-positive quantity, caught at the boundary")
    void quantityBelowMinimum() throws Exception {
        mockMvc.perform(reserve(HOT_EVENT, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("400 IDEMPOTENCY_KEY_REQUIRED when the header is absent")
    void missingIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .with(bearer(DEFAULT_USER, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    @DisplayName("blank and malformed Idempotency-Key values are client errors")
    void invalidIdempotencyKeys() throws Exception {
        String user = UUID.randomUUID().toString();

        mockMvc.perform(reserveAs(HOT_EVENT, " ", user, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        mockMvc.perform(reserveAs(HOT_EVENT, "short", user, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(reserveAs(HOT_EVENT, "x".repeat(129), user, 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("404 EVENT_NOT_FOUND for an unknown event")
    void unknownEvent() throws Exception {
        mockMvc.perform(reserve(UUID.randomUUID().toString(), 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("409 USER_LIMIT_EXCEEDED once the cap is reached")
    void userCap() throws Exception {
        String user = UUID.randomUUID().toString();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(reserveAs(HOT_EVENT, UUID.randomUUID().toString(), user, 1))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(reserveAs(HOT_EVENT, UUID.randomUUID().toString(), user, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("10 identical requests replay one reservation without repeating side effects")
    void identicalRetriesReplayTheOriginalReservation() throws Exception {
        String key = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(reserveAs(HOT_EVENT, key, user, 1))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andReturn();

        String reservationId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        String location = first.getResponse().getHeader("Location");

        Assertions.assertThat(location)
                .isEqualTo("/api/v1/reservations/" + reservationId);

        for (int retry = 1; retry < 10; retry++) {
            mockMvc.perform(reserveAs(HOT_EVENT, key, user, 1))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotency-Replayed", "true"))
                    .andExpect(header().string("Location", location))
                    .andExpect(jsonPath("$.id").value(reservationId));
        }

        Integer reservations = jdbc().queryForObject(
                "SELECT count(*) FROM reservation", Integer.class);
        Integer held = jdbc().queryForObject(
                "SELECT held FROM ticket_inventory WHERE event_id = ?::uuid", Integer.class, HOT_EVENT);
        Integer outboxEvents = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'reservation.created'", Integer.class);

        Assertions.assertThat(reservations).isEqualTo(1);
        Assertions.assertThat(held).isEqualTo(1);
        Assertions.assertThat(outboxEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("the same Idempotency-Key with a different request is a conflict")
    void reusedKeyWithDifferentFingerprintIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(reserveAs(HOT_EVENT, key, user, 1))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(reserveAs(HOT_EVENT, key, user, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));

        var reservation = jdbc().queryForMap(
                "SELECT id::text AS id, qty, status FROM reservation WHERE idempotency_key = ?", key);
        Integer held = jdbc().queryForObject(
                "SELECT held FROM ticket_inventory WHERE event_id = ?::uuid", Integer.class, HOT_EVENT);
        Integer outboxEvents = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'reservation.created'", Integer.class);

        Assertions.assertThat(reservation)
                .containsEntry("id", reservationId)
                .containsEntry("qty", 1)
                .containsEntry("status", "PENDING");
        Assertions.assertThat(held).isEqualTo(1);
        Assertions.assertThat(outboxEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE cancels once, returns inventory, and a repeat is a safe 409")
    void cancellationEndpointReleasesInventoryExactlyOnce() throws Exception {
        MvcResult created = mockMvc.perform(reserve(HOT_EVENT, 2))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(delete("/api/v1/reservations/{reservationId}", reservationId)
                        .with(bearer(DEFAULT_USER, "USER"))
                        .header("X-Request-Id", "cancel-api-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(reservationId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.terminatedAt").exists());

        mockMvc.perform(delete("/api/v1/reservations/{reservationId}", reservationId)
                        .with(bearer(DEFAULT_USER, "USER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));

        var inventory = jdbc().queryForMap(
                "SELECT available, held FROM ticket_inventory WHERE event_id = ?::uuid", HOT_EVENT);
        Integer cancelledEvents = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid "
                        + "AND type = 'reservation.cancelled' AND correlation_id = 'cancel-api-test'",
                Integer.class, reservationId);
        Assertions.assertThat(inventory).containsEntry("available", 100).containsEntry("held", 0);
        Assertions.assertThat(cancelledEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("POST confirm atomically creates an order, sells the hold, and writes the outbox event")
    void confirmationEndpointCommitsOrderInventoryAndOutboxTogether() throws Exception {
        MvcResult created = mockMvc.perform(reserve(HOT_EVENT, 2))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(confirm(reservationId, "tok_ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservation.id").value(reservationId))
                .andExpect(jsonPath("$.reservation.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.order.reservationId").value(reservationId))
                .andExpect(jsonPath("$.order.status").value("PAID"))
                .andExpect(jsonPath("$.order.amountMinor").value(9000))
                .andExpect(jsonPath("$.order.currency").value("EUR"));

        var inventory = jdbc().queryForMap(
                "SELECT available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);
        Integer orders = jdbc().queryForObject(
                "SELECT count(*) FROM ticket_order WHERE reservation_id = ?::uuid",
                Integer.class, reservationId);
        Integer confirmedEvents = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid "
                        + "AND type = 'reservation.confirmed' "
                        + "AND correlation_id = 'confirm-api-test' AND published_at IS NULL",
                Integer.class, reservationId);

        Assertions.assertThat(inventory)
                .containsEntry("available", 98)
                .containsEntry("held", 0)
                .containsEntry("sold", 2);
        Assertions.assertThat(orders).isEqualTo(1);
        Assertions.assertThat(confirmedEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("a declined payment leaves the hold pending and creates no order or confirmation event")
    void declinedPaymentDoesNotChangeBookingState() throws Exception {
        MvcResult created = mockMvc.perform(reserve(HOT_EVENT, 1))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(confirm(reservationId, "tok_declined"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));

        var state = jdbc().queryForMap("""
                SELECT r.status, i.available, i.held, i.sold,
                       (SELECT count(*) FROM ticket_order o WHERE o.reservation_id = r.id) orders,
                       (SELECT count(*) FROM outbox_event e
                        WHERE e.aggregate_id = r.id AND e.type = 'reservation.confirmed') confirmed_events
                FROM reservation r
                JOIN ticket_inventory i ON i.event_id = r.event_id
                WHERE r.id = ?::uuid
                """, reservationId);
        Assertions.assertThat(state)
                .containsEntry("status", "PENDING")
                .containsEntry("available", 99)
                .containsEntry("held", 1)
                .containsEntry("sold", 0);
        Assertions.assertThat(((Number) state.get("orders")).longValue()).isZero();
        Assertions.assertThat(((Number) state.get("confirmed_events")).longValue()).isZero();
    }

    @Test
    @DisplayName("a payment timeout is bounded, not retried, and leaves the hold pending")
    void timedOutPaymentDoesNotChangeBookingState() throws Exception {
        MvcResult created = mockMvc.perform(reserve(HOT_EVENT, 1))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        long started = System.nanoTime();
        mockMvc.perform(confirm(reservationId, "tok_timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TIMEOUT"));
        Assertions.assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(1));

        var state = jdbc().queryForMap("""
                SELECT r.status, i.available, i.held, i.sold,
                       (SELECT count(*) FROM ticket_order o WHERE o.reservation_id = r.id) orders,
                       (SELECT count(*) FROM outbox_event e
                        WHERE e.aggregate_id = r.id AND e.type = 'reservation.confirmed') confirmed_events
                FROM reservation r
                JOIN ticket_inventory i ON i.event_id = r.event_id
                WHERE r.id = ?::uuid
                """, reservationId);
        Assertions.assertThat(state)
                .containsEntry("status", "PENDING")
                .containsEntry("available", 99)
                .containsEntry("held", 1)
                .containsEntry("sold", 0);
        Assertions.assertThat(((Number) state.get("orders")).longValue()).isZero();
        Assertions.assertThat(((Number) state.get("confirmed_events")).longValue()).isZero();
    }

    @Test
    @DisplayName("DELETE returns 404 for an unknown reservation")
    void cancellationOfUnknownReservation() throws Exception {
        mockMvc.perform(delete("/api/v1/reservations/{reservationId}", UUID.randomUUID())
                        .with(bearer(DEFAULT_USER, "USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
    }
}
