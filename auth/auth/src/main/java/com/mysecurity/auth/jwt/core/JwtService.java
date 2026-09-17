package com.mysecurity.auth.jwt.core;

import com.mysecurity.auth.enums.TokenType;
import com.mysecurity.auth.jwt.strategy.TokenStrategy;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final TokenFactory tokenFactory;

    public String generateToken(UserDetails userDetails, Long id, TokenType tokenType) {
        TokenStrategy tokenStrategy = tokenFactory.getTokenStrategy(tokenType);
        return tokenStrategy.generateToken(userDetails, id);
    }

    public Claims extractAllClaims(String token, TokenType tokenType) {
        TokenStrategy tokenStrategy = tokenFactory.getTokenStrategy(tokenType);
        return tokenStrategy.extractAllClaims(token);
    }

    public boolean isTokenValid(String token, UserDetails userDetails, TokenType tokenType) {
        TokenStrategy tokenStrategy = tokenFactory.getTokenStrategy(tokenType);
        return tokenStrategy.isTokenValid(token, userDetails);
    }

    public String extractUsername(String token, TokenType tokenType) {
        TokenStrategy tokenStrategy = tokenFactory.getTokenStrategy(tokenType);
        return tokenStrategy.extractUsername(token);
    }

    public long extractTokenExpiration(TokenType tokenType) {
        TokenStrategy tokenStrategy = tokenFactory.getTokenStrategy(tokenType);
        return tokenStrategy.extractTokenExpiration(tokenType);
    }

    public String getTokenType(TokenType tokenType) {
        TokenStrategy tokenStrategy = tokenFactory.getTokenStrategy(tokenType);
        return tokenStrategy.getTokenType();
    }

}
