package com.ticketsystem.auth.jwt.strategy;

import com.ticketsystem.auth.config.AuthProperties;
import com.ticketsystem.auth.enums.TokenType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;

/** Shared validation prevents access and refresh strategies from drifting apart. */
abstract class SignedTokenStrategy implements TokenStrategy {
    private final SecretKey key;
    private final AuthProperties properties;
    private final TokenType type;
    private final Duration ttl;
    private final Clock clock;
    private final JwtParser parser;

    SignedTokenStrategy(SecretKey key, AuthProperties properties, TokenType type, Duration ttl, Clock clock) {
        this.key = key;
        this.properties = properties;
        this.type = type;
        this.ttl = ttl;
        this.clock = clock;
        parser = Jwts.parser().verifyWith(key)
                .sig().clear().add(Jwts.SIG.HS256).and()
                .requireIssuer(properties.issuer()).requireAudience(properties.audience())
                .require("type", type.claim())
                .clock(() -> Date.from(clock.instant()))
                .clockSkewSeconds(properties.clockSkew().toSeconds()).build();
    }

    @Override
    public String generateToken(UUID userId, UUID sessionId, Instant now) {
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        return Jwts.builder().header().type("JWT").and()
                .issuer(properties.issuer()).audience().add(properties.audience()).and()
                .subject(userId.toString())
                .id((type == TokenType.REFRESH ? sessionId : UUID.randomUUID()).toString())
                .claim("sid", sessionId.toString()).claim("type", type.claim())
                .issuedAt(Date.from(issuedAt)).notBefore(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }

    @Override
    public Claims extractAllClaims(String token) {
        if (token == null || token.isBlank() || token.length() > 4096) {
            throw new JwtException("Invalid token");
        }
        Claims claims = parser.parseSignedClaims(token).getPayload();
        try {
            requireUuid(claims.getSubject());
            requireUuid(claims.getId());
            requireUuid(claims.get("sid", String.class));
            if (claims.getIssuedAt() == null || claims.getExpiration() == null || claims.getNotBefore() == null
                    || !claims.getExpiration().after(claims.getIssuedAt())
                    || claims.getIssuedAt().toInstant().isAfter(clock.instant().plus(properties.clockSkew()))
                    || (type == TokenType.REFRESH && !claims.getId().equals(claims.get("sid", String.class)))) {
                throw new JwtException("Invalid token claims");
            }
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new JwtException("Invalid token claims", ex);
        }
        return claims;
    }

    private static void requireUuid(String value) {
        if (value == null || value.length() != 36) throw new IllegalArgumentException("Invalid identifier");
        UUID.fromString(value);
    }
}
