package com.ticketsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.auth.config.AuthProperties;
import com.ticketsystem.auth.enums.TokenType;
import com.ticketsystem.auth.jwt.core.JwtKeyProvider;
import com.ticketsystem.auth.jwt.core.JwtService;
import com.ticketsystem.auth.jwt.core.TokenFactory;
import com.ticketsystem.auth.jwt.strategy.AccessTokenStrategy;
import com.ticketsystem.auth.jwt.strategy.RefreshTokenStrategy;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JwtStrategyTest {
    static final String ACCESS_SECRET = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    static final String REFRESH_SECRET = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private final AuthProperties properties = properties(ACCESS_SECRET, REFRESH_SECRET);
    private final JwtKeyProvider keys = new JwtKeyProvider(properties);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtService jwt = new JwtService(new TokenFactory(
            new AccessTokenStrategy(keys, properties, clock), new RefreshTokenStrategy(keys, properties, clock)));

    static AuthProperties properties(String access, String refresh) {
        return new AuthProperties("test-issuer", "test-api", Duration.ofMinutes(15), Duration.ofDays(7),
                Duration.ZERO, access, refresh,
                new AuthProperties.Cookie("__Host-ticket_refresh", "__Host-ticket_csrf", "/", true, "Strict"),
                new AuthProperties.LoginProtection(5, Duration.ofMinutes(15), Duration.ofMinutes(1), 30, 10),
                new AuthProperties.BootstrapAdmin("", ""), List.of());
    }

    @Test
    void tokenPairHasExpectedLifetimesAndIdentityClaims() {
        for (TokenType type : TokenType.values()) {
            String token = jwt.generateToken(USER, SESSION, type, NOW);
            var claims = jwt.extractAllClaims(token, type);
            assertThat(claims.getSubject()).isEqualTo(USER.toString());
            assertThat(claims.get("sid", String.class)).isEqualTo(SESSION.toString());
            assertThat(claims.get("type", String.class)).isEqualTo(type.claim());
            assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(
                    type == TokenType.ACCESS ? Duration.ofMinutes(15) : Duration.ofDays(7)));
        }
    }

    @Test
    void accessAndRefreshTokensCannotSubstituteForEachOther() {
        String access = jwt.generateToken(USER, SESSION, TokenType.ACCESS, NOW);
        String refresh = jwt.generateToken(USER, SESSION, TokenType.REFRESH, NOW);
        assertThatThrownBy(() -> jwt.extractAllClaims(refresh, TokenType.ACCESS)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.extractAllClaims(access, TokenType.REFRESH)).isInstanceOf(JwtException.class);
    }

    @Test
    void wrongTypeIsRejectedEvenWhenSignedWithTheAccessKey() {
        reject(builder -> builder.claim("type", "refresh"));
    }

    @Test
    void missingAndIncorrectClaimsAreRejected() {
        reject(builder -> builder.issuer("other-service"));
        reject(builder -> builder.audience().clear().add("other-api"));
        reject(builder -> builder.subject("not-a-uuid"));
        reject(builder -> builder.id(null));
        reject(builder -> builder.claim("sid", null));
        reject(builder -> builder.expiration(null));
        reject(builder -> builder.issuedAt(null));
        reject(builder -> builder.notBefore(null));
        reject(builder -> builder.claim("type", null));
    }

    @Test
    void expiredAndFutureTokensAreRejected() {
        String expired = jwt.generateToken(USER, SESSION, TokenType.ACCESS, NOW.minus(Duration.ofMinutes(16)));
        assertThatThrownBy(() -> jwt.extractAllClaims(expired, TokenType.ACCESS)).isInstanceOf(JwtException.class);
        reject(builder -> builder.notBefore(Date.from(NOW.plusSeconds(60))));
        reject(builder -> builder.issuedAt(Date.from(NOW.plusSeconds(60))));
    }

    @Test
    void foreignSignatureAndUnsignedTokensAreRejected() {
        String foreign = validBuilder().signWith(Jwts.SIG.HS256.key().build(), Jwts.SIG.HS256).compact();
        String unsigned = validBuilder().compact();
        assertThatThrownBy(() -> jwt.extractAllClaims(foreign, TokenType.ACCESS)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwt.extractAllClaims(unsigned, TokenType.ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void startupRejectsMissingWeakMalformedOrReusedKeys() {
        assertThatThrownBy(() -> properties(null, REFRESH_SECRET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("c2hvcnQ=", REFRESH_SECRET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("not-base64!", REFRESH_SECRET)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(ACCESS_SECRET, ACCESS_SECRET)).isInstanceOf(IllegalArgumentException.class);
    }

    private JwtBuilder validBuilder() {
        return Jwts.builder().issuer(properties.issuer()).audience().add(properties.audience()).and()
                .subject(USER.toString()).id(UUID.randomUUID().toString()).claim("sid", SESSION.toString())
                .claim("type", "access").issuedAt(Date.from(NOW)).notBefore(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(900)));
    }

    private void reject(Consumer<JwtBuilder> mutation) {
        JwtBuilder builder = validBuilder();
        mutation.accept(builder);
        String token = builder.signWith(keys.getAccessKey(), Jwts.SIG.HS256).compact();
        assertThatThrownBy(() -> jwt.extractAllClaims(token, TokenType.ACCESS)).isInstanceOf(JwtException.class);
    }
}
