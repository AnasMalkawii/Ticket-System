package com.ticketsystem.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.catalog.cache.CatalogCacheProperties;
import com.ticketsystem.catalog.cache.CatalogCaches;
import com.ticketsystem.schema.AbstractPostgresRedisIT;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Public catalog, admin mutation, TTL, and explicit invalidation contract. */
@AutoConfigureMockMvc
class CatalogApiIT extends AbstractPostgresRedisIT {

    private static final UUID ADMIN =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID USER =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CatalogCacheProperties cacheProperties;

    @Test
    @DisplayName("public catalog pages, details, and advisory availability match the contract")
    void publicCatalogContract() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                        .queryParam("status", "ON_SALE")
                        .queryParam("page", "0")
                        .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(HOT_EVENT));

        mockMvc.perform(get("/api/v1/events/{id}", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aurora Live - Opening Night"))
                .andExpect(jsonPath("$.status").value("ON_SALE"));

        mockMvc.perform(get("/api/v1/events/{id}/availability", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(HOT_EVENT))
                .andExpect(jsonPath("$.total").value(100))
                .andExpect(jsonPath("$.available").value(100))
                .andExpect(jsonPath("$.advisory").value(true))
                .andExpect(jsonPath("$.asOf").exists());

        mockMvc.perform(get("/api/v1/events").queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/events/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Redis stores catalog DTOs with 60-second and 5-second TTLs")
    void cacheTtlsAndHits() throws Exception {
        assertThat(cacheProperties.eventTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(cacheProperties.availabilityTtl()).isEqualTo(Duration.ofSeconds(5));
        RedisCache availabilityCache = (RedisCache) cacheManager
                .getCache(CatalogCaches.AVAILABILITY);
        assertThat(availabilityCache).isNotNull();
        assertThat(availabilityCache.getCacheConfiguration().getTtlFunction()
                .getTimeToLive(HOT_EVENT, "snapshot")).isEqualTo(Duration.ofSeconds(5));

        mockMvc.perform(get("/api/v1/events/{id}", HOT_EVENT)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events").queryParam("status", "ON_SALE"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events/{id}/availability", HOT_EVENT))
                .andExpect(status().isOk());

        String detailKey = "ticketing::eventDetails::" + HOT_EVENT;
        String pageKey = "ticketing::eventPages::ON_SALE:0:20";
        String availabilityKey = "ticketing::availability::" + HOT_EVENT;
        assertTtl(detailKey, 1, 60);
        assertTtl(pageKey, 1, 60);
        assertTtl(availabilityKey, 1, 5);

        jdbc().update("UPDATE event SET name = 'Database-only change' WHERE id = ?::uuid",
                HOT_EVENT);
        mockMvc.perform(get("/api/v1/events/{id}", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Aurora Live - Opening Night"));
    }

    @Test
    @DisplayName("admin update commits first and explicitly invalidates every affected cache")
    void adminUpdateInvalidatesCaches() throws Exception {
        mockMvc.perform(get("/api/v1/events/{id}", HOT_EVENT)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/events/{id}/availability", HOT_EVENT))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/events/{id}", HOT_EVENT)
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Opening Night\",\"addTickets\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Opening Night"));

        assertThat(redis.hasKey("ticketing::eventDetails::" + HOT_EVENT)).isFalse();
        assertThat(redis.hasKey("ticketing::availability::" + HOT_EVENT)).isFalse();
        Set<String> remainingPages = redis.keys("ticketing::eventPages::*");
        assertThat(remainingPages).isEmpty();

        mockMvc.perform(get("/api/v1/events/{id}", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Opening Night"));
        mockMvc.perform(get("/api/v1/events/{id}/availability", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(105))
                .andExpect(jsonPath("$.available").value(105));

        mockMvc.perform(patch("/api/v1/admin/events/{id}", HOT_EVENT)
                        .with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forbidden change\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("admin creation writes event and inventory atomically and clears cached pages")
    void adminCreatesEventAndInventory() throws Exception {
        mockMvc.perform(get("/api/v1/events")).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/v1/admin/events")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Day 5 Showcase",
                                  "venue":"Main Hall",
                                  "startsAt":"2027-01-10T20:00:00Z",
                                  "saleStartsAt":"2026-12-01T10:00:00Z",
                                  "saleEndsAt":"2027-01-09T20:00:00Z",
                                  "totalTickets":75,
                                  "priceMinor":5200,
                                  "currency":"EUR"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith(
                        "/api/v1/events/")))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();

        String eventId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc().queryForObject(
                "SELECT total FROM ticket_inventory WHERE event_id = ?::uuid",
                Integer.class, eventId)).isEqualTo(75);
        assertThat(redis.keys("ticketing::eventPages::*")).isEmpty();
    }

    private void assertTtl(String key, long minimum, long maximum) {
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).as("TTL for %s", key).isBetween(minimum, maximum);
    }

    private static RequestPostProcessor asAdmin() {
        return jwt().jwt(token -> token.subject(ADMIN.toString()).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor asUser() {
        return jwt().jwt(token -> token.subject(USER.toString()).claim("role", "USER"))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
