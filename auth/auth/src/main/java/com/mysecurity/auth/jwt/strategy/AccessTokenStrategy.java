package com.mysecurity.auth.jwt.strategy;

import com.mysecurity.auth.enums.TokenType;
import com.mysecurity.auth.jwt.core.JwtKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class AccessTokenStrategy implements TokenStrategy{

    private final JwtKeyProvider jwtKeyProvider;

    @Value("${jwt.access-expiration}")
    private long expiration;

    @Override
    public String generateToken(UserDetails userDetails, Long id) {

        var authorities = userDetails.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .claim("type", "access")
                .claim("authorities", authorities)
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(jwtKeyProvider.getAccessKey())
                .compact();
    }

    @Override
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith((SecretKey) jwtKeyProvider.getAccessKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Override
    public boolean isTokenValid(String token, UserDetails userDetails) {
        Claims claims = extractAllClaims(token);
        String username = claims.getSubject();
        Date expirationDate = claims.getExpiration();
        return username.equals(userDetails.getUsername()) && expirationDate.after(new Date());
    }

    @Override
    public String extractUsername(String token) {
        Claims claims = extractAllClaims(token);
        return claims.getSubject();
    }

    @Override
    public String getTokenType() {
        return "access";
    }

    @Override
    public long extractTokenExpiration(TokenType tokenType) {
        return expiration;
    }
}
