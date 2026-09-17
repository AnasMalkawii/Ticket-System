package com.mysecurity;

import com.jayway.jsonpath.JsonPath;
import com.mysecurity.auth.enums.Role;
import com.mysecurity.auth.repository.RefreshTokenRepository;
import com.mysecurity.user.entity.User;
import com.mysecurity.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    private static final String USERNAME = "alice";
    private static final String PASSWORD = "password123";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------- registration ----------

    @Test
    void register_returnsCreated() throws Exception {
        mockMvc.perform(registerRequest(USERNAME, PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("User registered successfully"));
    }

    @Test
    void register_duplicateUsername_returnsConflict() throws Exception {
        mockMvc.perform(registerRequest(USERNAME, PASSWORD)).andExpect(status().isCreated());

        mockMvc.perform(registerRequest(USERNAME, PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username already exists"));
    }

    @Test
    void register_shortPassword_returnsBadRequest() throws Exception {
        mockMvc.perform(registerRequest(USERNAME, "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("password")));
    }

    @Test
    void register_invalidUsername_returnsBadRequest() throws Exception {
        mockMvc.perform(registerRequest("Alice_Uppercase", PASSWORD))
                .andExpect(status().isBadRequest());
    }

    // ---------- login ----------

    @Test
    void login_success_returnsTokenAndHttpOnlyCookie() throws Exception {
        register(USERNAME, PASSWORD);

        mockMvc.perform(loginRequest(USERNAME, PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged In successfully"))
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(cookie().exists("refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true));
    }

    @Test
    void login_wrongPassword_returnsUnauthorized() throws Exception {
        register(USERNAME, PASSWORD);

        mockMvc.perform(loginRequest(USERNAME, "wrongpassword"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Credentials"));
    }

    @Test
    void login_unknownUser_returnsUnauthorized() throws Exception {
        mockMvc.perform(loginRequest("ghost", PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Credentials"));
    }

    // ---------- protected access ----------

    @Test
    void protectedEndpoint_withoutToken_returnsUnauthorizedJson() throws Exception {
        mockMvc.perform(get("/api/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpStatus").value(401))
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
    }

    @Test
    void protectedEndpoint_withGarbageToken_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withValidToken_returnsCurrentUser() throws Exception {
        register(USERNAME, PASSWORD);
        String token = login(USERNAME, PASSWORD);

        mockMvc.perform(get("/api/user/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USERNAME))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    // ---------- role-based access ----------

    @Test
    void adminEndpoint_asNormalUser_returnsForbidden() throws Exception {
        register(USERNAME, PASSWORD);
        String token = login(USERNAME, PASSWORD);

        mockMvc.perform(get("/api/user/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.httpStatus").value(403));
    }

    @Test
    void adminEndpoint_asAdmin_returnsOk() throws Exception {
        userRepository.save(User.builder()
                .username("bossman")
                .password(passwordEncoder.encode(PASSWORD))
                .role(Role.ADMIN)
                .build());
        String token = login("bossman", PASSWORD);

        mockMvc.perform(get("/api/user/admin").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // ---------- refresh + rotation + reuse detection ----------

    @Test
    void refresh_withoutCookie_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_rotatesTokenAndSetsNewCookie() throws Exception {
        register(USERNAME, PASSWORD);
        MvcResult loginResult = mockMvc.perform(loginRequest(USERNAME, PASSWORD))
                .andExpect(status().isOk()).andReturn();
        String refreshToken = extractRefreshCookie(loginResult);

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(cookie().exists("refresh_token"));
    }

    @Test
    void reusingRotatedRefreshToken_revokesTheWholeFamily() throws Exception {
        register(USERNAME, PASSWORD);
        MvcResult loginResult = mockMvc.perform(loginRequest(USERNAME, PASSWORD))
                .andExpect(status().isOk()).andReturn();
        String token1 = extractRefreshCookie(loginResult);

        // First refresh: token1 -> token2 (token1 is now rotated/revoked)
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("refresh_token", token1)))
                .andExpect(status().isOk()).andReturn();
        String token2 = extractRefreshCookie(refreshResult);

        // Replaying token1 (already revoked) must be rejected as reuse...
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", token1)))
                .andExpect(status().isUnauthorized());

        // ...and must revoke the whole family, so even token2 no longer works.
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", token2)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- logout ----------

    @Test
    void logout_clearsCookieAndInvalidatesRefreshToken() throws Exception {
        register(USERNAME, PASSWORD);
        MvcResult loginResult = mockMvc.perform(loginRequest(USERNAME, PASSWORD))
                .andExpect(status().isOk()).andReturn();
        String refreshToken = extractRefreshCookie(loginResult);

        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logout successfully"))
                .andExpect(cookie().maxAge("refresh_token", 0));

        // The refresh token is revoked, so it can no longer be refreshed.
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie("refresh_token", refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder registerRequest(String u, String p) {
        return post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(u, p));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String u, String p) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body(u, p));
    }

    private String body(String username, String password) {
        return "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password);
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(registerRequest(username, password)).andExpect(status().isCreated());
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(username, password))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private String extractRefreshCookie(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).as("Set-Cookie header").isNotNull();
        Matcher matcher = Pattern.compile("refresh_token=([^;]*)").matcher(header);
        assertThat(matcher.find()).as("refresh_token in Set-Cookie").isTrue();
        return matcher.group(1);
    }
}
