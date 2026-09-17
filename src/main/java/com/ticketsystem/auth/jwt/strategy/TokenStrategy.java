package com.ticketsystem.auth.jwt.strategy;

import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.UUID;

public interface TokenStrategy {
    String generateToken(UUID userId, UUID sessionId, Instant now);
    Claims extractAllClaims(String token);
}
