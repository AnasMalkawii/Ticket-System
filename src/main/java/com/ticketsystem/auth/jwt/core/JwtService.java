package com.ticketsystem.auth.jwt.core;

import com.ticketsystem.auth.enums.TokenType;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class JwtService {
    private final TokenFactory factory;

    public JwtService(TokenFactory factory) { this.factory = factory; }

    public String generateToken(UUID userId, UUID sessionId, TokenType type, Instant now) {
        return factory.getTokenStrategy(type).generateToken(userId, sessionId, now);
    }

    public Claims extractAllClaims(String token, TokenType type) {
        return factory.getTokenStrategy(type).extractAllClaims(token);
    }
}
