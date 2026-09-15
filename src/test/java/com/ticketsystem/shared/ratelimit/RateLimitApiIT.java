package com.ticketsystem.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.schema.AbstractPostgresRedisIT;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Distributed user/IP limiter acceptance tests against a real Redis server. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ticketing.rate-limit.enabled=true",
        "ticketing.rate-limit.per-user=2",
        "ticketing.rate-limit.per-ip=3",
        "ticketing.rate-limit.window=1m"
})
class RateLimitApiIT extends AbstractPostgresRedisIT {

    private static final UUID USER =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReserveRateLimiter limiter;

    @Test
    @DisplayName("the third attempt by one user is a distinct 429 with Retry-After")
    void perUserLimit() throws Exception {
        mockMvc.perform(reserve(USER, "192.0.2.10"))
                .andExpect(status().isCreated());
        mockMvc.perform(reserve(USER, "192.0.2.11"))
                .andExpect(status().isCreated());

        MvcResult limited = mockMvc.perform(reserve(USER, "192.0.2.12"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andReturn();

        int retryAfter = Integer.parseInt(limited.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1, 60);
        assertTraceMatchesRequestId(limited);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the fourth attempt from one IP is limited across distinct users")
    void perIpLimit() throws Exception {
        String clientIp = "198.51.100.42";
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(reserve(UUID.randomUUID(), clientIp))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(reserve(UUID.randomUUID(), clientIp))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        assertThat(jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("one Redis script admits exactly the configured user limit under concurrency")
    void atomicConcurrentLimit() throws Exception {
        int callers = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, callers)
                    .mapToObj(index -> pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            limiter.check(USER, "203.0.113." + index);
                            return true;
                        } catch (RateLimitExceededException limited) {
                            return false;
                        }
                    }))
                    .toList();
            start.countDown();

            int allowed = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(30, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(2);
        }
    }

    private MockHttpServletRequestBuilder reserve(UUID userId, String remoteAddress) {
        return post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                .with(jwt().jwt(token -> token
                                .subject(userId.toString())
                                .claim("role", "USER"))
                        .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .with(request -> {
                    request.setRemoteAddr(remoteAddress);
                    return request;
                })
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}");
    }

    private static void assertTraceMatchesRequestId(MvcResult result) throws Exception {
        String requestId = result.getResponse().getHeader("X-Request-Id");
        String traceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
        assertThat(requestId).isNotBlank().isEqualTo(traceId);
    }
}
