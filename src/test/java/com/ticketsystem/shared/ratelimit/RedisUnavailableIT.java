package com.ticketsystem.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** F-09 acceptance gate: Redis loss degrades speed/protection, never booking correctness. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ticketing.rate-limit.enabled=true",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=63999",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms"
})
class RedisUnavailableIT extends AbstractPostgresIT {

    private static final UUID USER =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID ADMIN =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("catalog falls back and booking still commits correctly while Redis is unavailable")
    void redisFailureDoesNotBecomeBookingFailure() throws Exception {
        mockMvc.perform(get("/api/v1/events/{id}", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aurora Live - Opening Night"));

        MvcResult reservation = mockMvc.perform(post(
                        "/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .with(asUser())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Request-Id", "redis-outage-booking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", "redis-outage-booking"))
                .andReturn();

        String reservationId = JsonPath.read(
                reservation.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc().queryForObject(
                "SELECT correlation_id FROM outbox_event WHERE aggregate_id = ?::uuid",
                String.class, reservationId)).isEqualTo("redis-outage-booking");

        Map<String, Object> inventory = jdbc().queryForMap(
                "SELECT total, available, held, sold FROM ticket_inventory "
                        + "WHERE event_id = ?::uuid", HOT_EVENT);
        assertThat(inventory)
                .containsEntry("total", 100)
                .containsEntry("available", 98)
                .containsEntry("held", 2)
                .containsEntry("sold", 0);
        Map<String, Object> reconciliation = jdbc().queryForMap(
                "SELECT conservation_drift, held_drift, sold_drift "
                        + "FROM v_inventory_reconciliation WHERE event_id = ?::uuid", HOT_EVENT);
        assertThat(((Number) reconciliation.get("conservation_drift")).longValue()).isZero();
        assertThat(((Number) reconciliation.get("held_drift")).longValue()).isZero();
        assertThat(((Number) reconciliation.get("sold_drift")).longValue()).isZero();
    }

    @Test
    @DisplayName("a committed admin update is not rolled back when cache invalidation fails")
    void adminMutationSurvivesRedisFailure() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/events/{id}", HOT_EVENT)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Redis-independent catalog\",\"addTickets\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Redis-independent catalog"));

        assertThat(jdbc().queryForObject(
                "SELECT name FROM event WHERE id = ?::uuid", String.class, HOT_EVENT))
                .isEqualTo("Redis-independent catalog");
        assertThat(jdbc().queryForObject(
                "SELECT total FROM ticket_inventory WHERE event_id = ?::uuid",
                Integer.class, HOT_EVENT)).isEqualTo(103);
    }

    private static RequestPostProcessor asUser() {
        return jwt().jwt(token -> token.subject(USER.toString()).claim("role", "USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private static RequestPostProcessor asAdmin() {
        return jwt().jwt(token -> token.subject(ADMIN.toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
