package com.ticketsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.util.UUID;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** End-to-end proof for JWT identity, roles, ownership, and secured diagnostics. */
@AutoConfigureMockMvc
class SecurityApiIT extends AbstractPostgresIT {

    private static final String REFRESH_COOKIE = "__Host-ticket_refresh";
    private static final String CSRF_COOKIE = "__Host-ticket_csrf";

    private static final UUID USER_A =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID USER_B =
            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID ADMIN =
            UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("catalog is public while reservation writes require a bearer token")
    void publicAndProtectedRoutesAreSeparated() throws Exception {
        mockMvc.perform(get("/api/v1/events/{eventId}", HOT_EVENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(HOT_EVENT));

        MvcResult unauthorized = mockMvc.perform(post(
                        "/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();

        assertResponseTraceMatchesHeader(unauthorized);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("configured credentials issue a real signed JWT accepted by the reserve API")
    void loginToProtectedEndpointRoundTrip() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"test-user","password":"test-user-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        MvcResult reserved = mockMvc.perform(post(
                        "/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Request-Id", "security-round-trip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", "security-round-trip"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(jsonPath("$.userId").value(USER_A.toString()))
                .andReturn();

        String reservationId = JsonPath.read(
                reserved.getResponse().getContentAsString(), "$.id");
        assertThat(jdbc().queryForObject(
                "SELECT correlation_id FROM outbox_event WHERE aggregate_id = ?::uuid",
                String.class, reservationId)).isEqualTo("security-round-trip");
    }

    @Test
    @DisplayName("bad credentials and malformed bearer tokens use the same 401 problem contract")
    void authenticationFailuresAreStructured() throws Exception {
        MvcResult badPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"test-user","password":"definitely-wrong"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();
        assertResponseTraceMatchesHeader(badPassword);

        MvcResult malformedToken = mockMvc.perform(post(
                        "/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andReturn();
        assertResponseTraceMatchesHeader(malformedToken);
    }

    @Test
    @DisplayName("repeated bad passwords lock the account without revealing account state")
    void repeatedFailuresLockAccount() throws Exception {
        String knownFailure = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"test-user","password":"wrong-password"}
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            knownFailure = JsonPath.read(result.getResponse().getContentAsString(), "$.detail");
        }
        MvcResult locked = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"test-user","password":"test-user-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();
        MvcResult unknown = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"unknown-user","password":"test-user-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(JsonPath.<String>read(locked.getResponse().getContentAsString(), "$.detail"))
                .isEqualTo(knownFailure)
                .isEqualTo(JsonPath.read(unknown.getResponse().getContentAsString(), "$.detail"));
        assertThat(jdbc().queryForObject(
                "SELECT locked_until IS NOT NULL FROM app_user WHERE id = ?::uuid",
                Boolean.class, USER_A)).isTrue();
    }

    @Test
    @DisplayName("disabling an account invalidates its active access token immediately")
    void disabledAccountInvalidatesAccessToken() throws Exception {
        MvcResult login = loginAsTestUser();
        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        jdbc().update("UPDATE app_user SET enabled = FALSE WHERE id = ?::uuid", USER_A);

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("disabled accounts cannot refresh and their token family stays revoked")
    void disabledAccountCannotRefresh() throws Exception {
        MvcResult login = loginAsTestUser();
        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        String refresh = responseCookie(login, REFRESH_COOKIE);
        String csrf = responseCookie(login, CSRF_COOKIE);
        jdbc().update("UPDATE app_user SET enabled = FALSE WHERE id = ?::uuid", USER_A);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, refresh),
                                new Cookie(CSRF_COOKIE, csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM auth_session WHERE user_id = ?::uuid",
                Integer.class, USER_A)).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM auth_session WHERE user_id = ?::uuid AND revoked_at IS NULL",
                Integer.class, USER_A)).isZero();

        jdbc().update("UPDATE app_user SET enabled = TRUE WHERE id = ?::uuid", USER_A);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, refresh),
                                new Cookie(CSRF_COOKIE, csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("registration stores a one-way password hash and supports login")
    void registrationAndLoginUseDatabaseIdentity() throws Exception {
        String password = "a-strong-demo-password";
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"New.Customer","password":"a-strong-demo-password"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("new.customer"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();

        String userId = JsonPath.read(registered.getResponse().getContentAsString(), "$.id");
        String storedHash = jdbc().queryForObject(
                "SELECT password_hash FROM app_user WHERE id = ?::uuid", String.class, userId);
        assertThat(storedHash).startsWith("{bcrypt}").doesNotContain(password);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"NEW.CUSTOMER","password":"a-strong-demo-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"new.customer","password":"another-strong-password"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_UNAVAILABLE"));
    }

    @Test
    @DisplayName("refresh rotates credentials and replay revokes the complete token family")
    void refreshRotationDetectsReplay() throws Exception {
        MvcResult login = loginAsTestUser();
        String oldRefresh = responseCookie(login, REFRESH_COOKIE);
        String oldCsrf = responseCookie(login, CSRF_COOKIE);

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, oldRefresh),
                                new Cookie(CSRF_COOKIE, oldCsrf))
                        .header("X-CSRF-TOKEN", oldCsrf))
                .andExpect(status().isOk())
                .andReturn();
        String newAccess = JsonPath.read(
                refreshed.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, oldRefresh),
                                new Cookie(CSRF_COOKIE, oldCsrf))
                        .header("X-CSRF-TOKEN", oldCsrf))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout clears cookies and immediately revokes issued access tokens")
    void logoutRevokesAccessToken() throws Exception {
        MvcResult login = loginAsTestUser();
        String access = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");
        String refresh = responseCookie(login, REFRESH_COOKIE);
        String csrf = responseCookie(login, CSRF_COOKIE);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(REFRESH_COOKIE, refresh),
                                new Cookie(CSRF_COOKIE, csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNoContent())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItems(
                                org.hamcrest.Matchers.containsString(REFRESH_COOKIE + "="),
                                org.hamcrest.Matchers.containsString(CSRF_COOKIE + "="))));

        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh requires matching cookie and header CSRF credentials")
    void refreshRequiresDoubleSubmitCsrf() throws Exception {
        MvcResult login = loginAsTestUser();
        String refresh = responseCookie(login, REFRESH_COOKIE);
        String csrf = responseCookie(login, CSRF_COOKIE);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, refresh),
                                new Cookie(CSRF_COOKIE, csrf)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(REFRESH_COOKIE, refresh),
                                new Cookie(CSRF_COOKIE, csrf))
                        .header("X-CSRF-TOKEN", "attacker-value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reservation ownership is enforced inside reads and cancellation")
    void ownerAndAdminPoliciesPreventHorizontalEscalation() throws Exception {
        MvcResult created = mockMvc.perform(reserveAs(USER_A, 2))
                .andExpect(status().isCreated())
                .andReturn();
        String reservationId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/v1/reservations/{id}", reservationId).with(asUser(USER_B)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId).with(asUser(USER_B)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/v1/reservations/{id}", reservationId).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId).with(asAdmin()))
                .andExpect(status().isForbidden());

        assertThat(jdbc().queryForObject(
                "SELECT status FROM reservation WHERE id = ?::uuid", String.class, reservationId))
                .isEqualTo("PENDING");
        assertThat(jdbc().queryForObject(
                "SELECT held FROM ticket_inventory WHERE event_id = ?::uuid",
                Integer.class, HOT_EVENT)).isEqualTo(2);

        mockMvc.perform(delete("/api/v1/reservations/{id}", reservationId).with(asUser(USER_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("request data cannot override the authenticated JWT subject")
    void requestBodyCannotOverrideJwtSubject() throws Exception {
        MvcResult login = loginAsTestUser();
        String token = JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"userId\":\"" + USER_B + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("strict boundary validation rejects unknown, oversized, and unsafe input")
    void strictBoundaryValidationRunsBeforeDatabaseWork() throws Exception {
        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .with(asUser(USER_A))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .with(asUser(USER_A))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1,\"padding\":\"" + "x".repeat(17_000) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        MvcResult badRequestId = mockMvc.perform(post(
                        "/api/v1/events/{eventId}/reservations", HOT_EVENT)
                        .with(asUser(USER_A))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Request-Id", "x".repeat(129))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn();
        assertResponseTraceMatchesHeader(badRequestId);

        assertThat(jdbc().queryForObject("SELECT count(*) FROM reservation", Integer.class))
                .isZero();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM outbox_event", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("health and Prometheus are scrapeable while diagnostic metrics require an administrator")
    void actuatorExposureIsLeastPrivilege() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "ticketing_reservation_attempts_total")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp_connections")));

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/actuator/metrics").with(asUser(USER_A)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/actuator/metrics").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reserveAs(
            UUID userId, int quantity) {
        return post("/api/v1/events/{eventId}/reservations", HOT_EVENT)
                .with(asUser(userId))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":%d}".formatted(quantity));
    }

    private RequestPostProcessor asUser(UUID userId) {
        return bearer(userId.toString(), "USER");
    }

    private RequestPostProcessor asAdmin() {
        return bearer(ADMIN.toString(), "ADMIN");
    }

    private static void assertResponseTraceMatchesHeader(MvcResult result) throws Exception {
        String requestId = result.getResponse().getHeader("X-Request-Id");
        String traceId = JsonPath.read(result.getResponse().getContentAsString(), "$.traceId");
        assertThat(requestId).isNotBlank().isEqualTo(traceId);
    }

    private MvcResult loginAsTestUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"test-user","password":"test-user-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().stringValues(HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItems(
                                org.hamcrest.Matchers.containsString(
                                        REFRESH_COOKIE + "="),
                                org.hamcrest.Matchers.containsString(
                                        CSRF_COOKIE + "="))))
                .andReturn();
        String refreshHeader = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(REFRESH_COOKIE + "="))
                .findFirst().orElseThrow();
        String csrfHeader = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(CSRF_COOKIE + "="))
                .findFirst().orElseThrow();
        assertThat(refreshHeader).contains("HttpOnly", "SameSite=Strict");
        assertThat(csrfHeader).doesNotContain("HttpOnly").contains("SameSite=Strict");
        return result;
    }

    private static String responseCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
                .findFirst()
                .orElseThrow();
    }
}
