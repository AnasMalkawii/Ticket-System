package com.mysecurity.auth.jwt.strategy;

import com.mysecurity.auth.enums.TokenType;
import io.jsonwebtoken.Claims;
import org.springframework.security.core.userdetails.UserDetails;

public interface TokenStrategy {

    String generateToken(UserDetails userDetails, Long id);

    Claims extractAllClaims(String token);

    boolean isTokenValid(String token, UserDetails userDetails);

    default String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    String getTokenType();

    long extractTokenExpiration(TokenType tokenType);

}
