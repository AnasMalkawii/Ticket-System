package com.mysecurity.auth.jwt.strategy;

import com.mysecurity.auth.enums.Role;
import com.mysecurity.auth.jwt.core.JwtKeyProvider;
import com.mysecurity.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtStrategyTest {

    // Throwaway dev secrets (base64, decode to >= 32 bytes).
    private static final String ACCESS_SECRET = "OWQyYTNtOGE0bTBhbGsyMThpMTJrZjk1YW5mODJhNGQxbHNiNGUzZDJqc2YzYTU=";
    private static final String REFRESH_SECRET = "N2I1YTNkMjQ0YTNlNjQ2ZjdhNTU2YjU4NGUzMjdhNGQ2YzViNGUzZDQ0NWYzYTU=";

    private AccessTokenStrategy accessStrategy;
    private RefreshTokenStrategy refreshStrategy;

    private final UserDetails alice = User.builder()
            .username("alice").password("x").role(Role.USER).build();

    @BeforeEach
    void setUp() {
        JwtKeyProvider keyProvider = new JwtKeyProvider();
        ReflectionTestUtils.setField(keyProvider, "accessSecret", ACCESS_SECRET);
        ReflectionTestUtils.setField(keyProvider, "refreshSecret", REFRESH_SECRET);

        accessStrategy = new AccessTokenStrategy(keyProvider);
        ReflectionTestUtils.setField(accessStrategy, "expiration", 900_000L);

        refreshStrategy = new RefreshTokenStrategy(keyProvider);
        ReflectionTestUtils.setField(refreshStrategy, "expiration", 604_800_000L);
    }

    @Test
    void accessTokenRoundTrips() {
        String token = accessStrategy.generateToken(alice, 1L);

        assertThat(accessStrategy.extractUsername(token)).isEqualTo("alice");
        assertThat(accessStrategy.isTokenValid(token, alice)).isTrue();

        Claims claims = accessStrategy.extractAllClaims(token);
        assertThat(claims.get("type")).isEqualTo("access");
        assertThat(claims.get("authorities", java.util.List.class)).contains("ROLE_USER");
    }

    @Test
    void refreshTokenCarriesTheJti() {
        String token = refreshStrategy.generateToken(alice, 42L);

        Claims claims = refreshStrategy.extractAllClaims(token);
        assertThat(claims.getId()).isEqualTo("42");
        assertThat(claims.get("type")).isEqualTo("refresh");
        assertThat(refreshStrategy.isTokenValid(token, alice)).isTrue();
    }

    @Test
    void tokenIsInvalidForADifferentUser() {
        String token = accessStrategy.generateToken(alice, 1L);
        UserDetails bob = User.builder().username("bob").password("x").role(Role.USER).build();

        assertThat(accessStrategy.isTokenValid(token, bob)).isFalse();
    }

    @Test
    void accessAndRefreshUseSeparateKeys() {
        // A token signed with the access key must not verify against the refresh key.
        String accessToken = accessStrategy.generateToken(alice, 1L);
        assertThatThrownBy(() -> refreshStrategy.extractAllClaims(accessToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void expiredTokenThrows() {
        ReflectionTestUtils.setField(accessStrategy, "expiration", -10_000L); // already expired
        String expired = accessStrategy.generateToken(alice, 1L);

        assertThatThrownBy(() -> accessStrategy.extractAllClaims(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = accessStrategy.generateToken(alice, 1L);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> accessStrategy.extractAllClaims(tampered))
                .isInstanceOf(JwtException.class);
    }
}
