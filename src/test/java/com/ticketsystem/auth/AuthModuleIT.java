package com.ticketsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.auth.enums.TokenType;
import com.ticketsystem.auth.jwt.core.JwtService;
import com.ticketsystem.auth.util.TokenHash;
import com.ticketsystem.schema.AbstractPostgresIT;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Security regressions specific to adapting the standalone auth module to Ticket System. */
@AutoConfigureMockMvc
class AuthModuleIT extends AbstractPostgresIT {
    private static final String REFRESH = "__Host-ticket_refresh";
    private static final String CSRF = "__Host-ticket_csrf";
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private org.springframework.context.ApplicationContext context;

    @Test
    void onlyTheIntegratedAuthChainIsConfigured() {
        assertThat(context.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class)).hasSize(1);
        assertThat(context.getBeansOfType(org.springframework.security.provisioning.InMemoryUserDetailsManager.class)).isEmpty();
        assertThat(context.getBean("jwtFilterRegistration", org.springframework.boot.web.servlet.FilterRegistrationBean.class).isEnabled())
                .isFalse();
    }

    @Test
    void registerCannotEscalateItsRoleAndUnicodePasswordsRespectBcryptByteLimit() throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"attacker\",\"password\":\"valid-long-password\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"unicode-user\",\"password\":\"" + "ش".repeat(40) + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM app_user WHERE username IN ('attacker','unicode-user')", Integer.class)).isZero();
    }

    @Test
    void loginIssuesJwtPairButDatabaseNeverStoresTheRawCredentials() throws Exception {
        Session session = login("test-user", "test-user-password");
        var accessClaims = jwt.extractAllClaims(session.access(), TokenType.ACCESS);
        var refreshClaims = jwt.extractAllClaims(session.refresh(), TokenType.REFRESH);
        assertThat(Duration.between(accessClaims.getIssuedAt().toInstant(), accessClaims.getExpiration().toInstant()))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(Duration.between(refreshClaims.getIssuedAt().toInstant(), refreshClaims.getExpiration().toInstant()))
                .isEqualTo(Duration.ofDays(7));
        assertThat(accessClaims.getSubject()).isEqualTo(USER.toString());
        assertThat(jdbc().queryForObject("SELECT token_hash FROM auth_session WHERE id = ?::uuid", String.class, refreshClaims.getId()))
                .isEqualTo(TokenHash.hash(session.refresh())).isNotEqualTo(session.refresh());
        assertThat(jdbc().queryForObject("SELECT csrf_hash FROM auth_session WHERE id = ?::uuid", String.class, refreshClaims.getId()))
                .isEqualTo(TokenHash.hash(session.csrf())).isNotEqualTo(session.csrf());
    }

    @Test
    void adminApiRejectsNormalUserAndAcceptsLoggedInAdmin() throws Exception {
        Session user = login("test-user", "test-user-password");
        Session admin = login("test-admin", "test-admin-password");
        mvc.perform(patch("/api/v1/admin/events/{id}", HOT_EVENT).header(HttpHeaders.AUTHORIZATION, "Bearer " + user.access())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"unauthorized\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.access())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/events/{id}", HOT_EVENT).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Auth integrated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Auth integrated"));
    }

    @Test
    void databaseRoleChangesApplyToExistingTokens() throws Exception {
        Session admin = login("test-admin", "test-admin-password");
        jdbc().update("UPDATE app_user SET role = 'USER' WHERE username = 'test-admin'");
        try {
            mvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin.access()))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc().update("UPDATE app_user SET role = 'ADMIN' WHERE username = 'test-admin'");
        }
    }

    @Test
    void expiredWrongTypeTamperedAndUnpersistedTokensAreRejectedThroughTheFilter() throws Exception {
        Session session = login("test-user", "test-user-password");
        var claims = jwt.extractAllClaims(session.access(), TokenType.ACCESS);
        UUID id = UUID.fromString(claims.get("sid", String.class));
        String expired = jwt.generateToken(USER, id, TokenType.ACCESS, Instant.now().minus(Duration.ofMinutes(16)));
        String missingSession = jwt.generateToken(USER, UUID.randomUUID(), TokenType.ACCESS, Instant.now());
        String[] pieces = session.access().split("\\.");
        String signature = pieces[2];
        String tampered = pieces[0] + "." + pieces[1] + "." + (signature.charAt(0) == 'A' ? "B" : "A") + signature.substring(1);
        for (String token : new String[] {expired, missingSession, tampered, session.refresh()}) {
            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH, session.access()), new Cookie(CSRF, session.csrf()))
                        .header("X-CSRF-TOKEN", session.csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyBearerHeaderAuthenticatesAndDuplicateHeadersAreRejected() throws Exception {
        Session session = login("test-user", "test-user-password");
        mvc.perform(get("/api/v1/auth/me").queryParam("access_token", session.access()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(new Cookie(REFRESH, session.refresh())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access(), "Bearer " + session.access()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "bearer " + session.access()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/not-allowlisted").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access()))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredAuthorizationHeaderDoesNotPreventCookieAuthenticatedRefreshOrLogout() throws Exception {
        Session session = login("test-user", "test-user-password");
        UUID id = UUID.fromString(jwt.extractAllClaims(session.refresh(), TokenType.REFRESH).getId());
        String expired = jwt.generateToken(USER, id, TokenType.ACCESS, Instant.now().minus(Duration.ofMinutes(16)));
        MvcResult refreshed = mvc.perform(refresh(session).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isOk()).andReturn();
        String csrf = cookie(refreshed, CSRF);
        mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .cookie(new Cookie(REFRESH, cookie(refreshed, REFRESH)), new Cookie(CSRF, csrf))
                        .header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNoContent());
    }

    @Test
    void disabledAccountCannotLoginOrRefreshFromAnotherSession() throws Exception {
        Session first = login("test-user", "test-user-password");
        Session second = login("test-user", "test-user-password");
        jdbc().update("UPDATE app_user SET enabled = FALSE WHERE id = ?::uuid", USER);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-user\",\"password\":\"test-user-password\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(refresh(first)).andExpect(status().isUnauthorized());
        mvc.perform(refresh(second)).andExpect(status().isUnauthorized());
        assertThat(jdbc().queryForObject("SELECT count(*) FROM auth_session WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void missingCsrfCannotLogoutOrRevokeAHealthySession() throws Exception {
        Session session = login("test-user", "test-user-password");
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie(REFRESH, session.refresh())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access()))
                .andExpect(status().isOk());
        mvc.perform(refresh(session)).andExpect(status().isOk());
    }

    @Test
    void passwordLockoutDoesNotLetAnAttackerTerminateAnExistingSession() throws Exception {
        Session session = login("test-user", "test-user-password");
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"test-user\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access()))
                .andExpect(status().isOk());
        mvc.perform(refresh(session)).andExpect(status().isOk());
    }

    @Test
    void validSignatureStillRequiresExactStoredRefreshHashAndOwner() throws Exception {
        Session session = login("test-user", "test-user-password");
        UUID id = UUID.fromString(jwt.extractAllClaims(session.refresh(), TokenType.REFRESH).getId());
        String unissued = jwt.generateToken(USER, id, TokenType.REFRESH, Instant.now().plusSeconds(1));
        String otherOwner = jwt.generateToken(UUID.randomUUID(), id, TokenType.REFRESH, Instant.now());
        for (String token : new String[] {unissued, otherOwner}) {
            mvc.perform(refresh(new Session(session.access(), token, session.csrf())))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(refresh(session)).andExpect(status().isOk());
    }

    @Test
    void replayRevokesOnlyTheAffectedLoginFamily() throws Exception {
        Session first = login("test-user", "test-user-password");
        Session separateLogin = login("test-user", "test-user-password");
        MvcResult rotated = mvc.perform(refresh(first)).andExpect(status().isOk()).andReturn();
        String newAccess = JsonPath.read(rotated.getResponse().getContentAsString(), "$.accessToken");
        mvc.perform(refresh(first)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccess))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + separateLogin.access()))
                .andExpect(status().isOk());
        mvc.perform(refresh(separateLogin)).andExpect(status().isOk());
    }

    @Test
    void simultaneousRefreshHasOneWinnerAndReplayRevocationPersists() throws Exception {
        Session session = login("test-user", "test-user-password");
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); return mvc.perform(refresh(session)).andReturn(); });
            var second = executor.submit(() -> { start.await(); return mvc.perform(refresh(session)).andReturn(); });
            start.countDown();
            MvcResult result1 = first.get(20, TimeUnit.SECONDS);
            MvcResult result2 = second.get(20, TimeUnit.SECONDS);
            assertThat(new int[] {result1.getResponse().getStatus(), result2.getResponse().getStatus()})
                    .containsExactlyInAnyOrder(200, 401);
            MvcResult winner = result1.getResponse().getStatus() == 200 ? result1 : result2;
            String access = JsonPath.read(winner.getResponse().getContentAsString(), "$.accessToken");
            mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(jdbc().queryForObject("SELECT count(*) FROM auth_session WHERE user_id = ?::uuid", Integer.class, USER)).isEqualTo(2);
        assertThat(jdbc().queryForObject("SELECT count(*) FROM auth_session WHERE revoked_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void simultaneousRegistrationReturnsCreatedAndConflictWithoutServerError() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> register = () -> {
                start.await();
                return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"race-user\",\"password\":\"strong-test-password\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(register);
            var second = executor.submit(register);
            start.countDown();
            assertThat(new int[] {first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)})
                    .containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void corsAllowsConfiguredBrowserOriginAndRejectsUnknownOrigins() throws Exception {
        mvc.perform(options("/api/v1/auth/refresh").header(HttpHeaders.ORIGIN, "https://ticket-ui.example.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-CSRF-TOKEN"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://ticket-ui.example.test"));
        mvc.perform(options("/api/v1/auth/refresh").header(HttpHeaders.ORIGIN, "https://untrusted.example.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void upgradingV9PreservesAccountsAndBookingsAndRevokesLegacySessions() throws Exception {
        Session session = login("test-user", "test-user-password");
        mvc.perform(post("/api/v1/events/{id}/reservations", HOT_EVENT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access())
                        .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isCreated());
        String schema = "auth_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        try {
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("9").load().migrate();
            for (String table : new String[] {"app_user", "auth_session", "event", "ticket_inventory", "reservation"}) {
                jdbc().execute("INSERT INTO " + schema + "." + table + " SELECT * FROM public." + table);
            }
            jdbc().update("UPDATE " + schema + ".auth_session SET token_hash = ?", TokenHash.hash("legacy-opaque-refresh"));
            org.flywaydb.core.Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc().queryForObject("SELECT count(*) FROM " + schema + ".auth_session WHERE revoked_at IS NULL", Integer.class)).isZero();
            assertThat(jdbc().queryForObject("SELECT count(*) FROM " + schema + ".auth_session", Integer.class)).isEqualTo(1);
            assertThat(jdbc().queryForObject("SELECT username FROM " + schema + ".app_user WHERE id = ?::uuid", String.class, USER)).isEqualTo("test-user");
            assertThat(jdbc().queryForObject("SELECT user_id FROM " + schema + ".reservation", UUID.class)).isEqualTo(USER);
            assertThat(jdbc().queryForObject("SELECT password_hash FROM " + schema + ".app_user WHERE id = ?::uuid", String.class, USER))
                    .isEqualTo(jdbc().queryForObject("SELECT password_hash FROM app_user WHERE id = ?::uuid", String.class, USER));
        } finally {
            // This identifier is generated above; only this test's isolated schema is removed.
            jdbc().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Session login(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().filter(s -> s.startsWith(REFRESH + "=")).findFirst().orElseThrow())
                .contains("Secure", "HttpOnly", "SameSite=Strict", "Max-Age=604800");
        return new Session(JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken"), cookie(result, REFRESH), cookie(result, CSRF));
    }

    private MockHttpServletRequestBuilder refresh(Session session) {
        return post("/api/v1/auth/refresh").cookie(new Cookie(REFRESH, session.refresh()), new Cookie(CSRF, session.csrf()))
                .header("X-CSRF-TOKEN", session.csrf());
    }

    private static String cookie(MvcResult result, String name) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream().filter(s -> s.startsWith(name + "="))
                .map(s -> s.substring(name.length() + 1, s.indexOf(';'))).findFirst().orElseThrow();
    }

    private record Session(String access, String refresh, String csrf) {}
}
