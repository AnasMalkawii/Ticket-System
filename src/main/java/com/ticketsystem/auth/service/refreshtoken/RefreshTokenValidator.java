package com.ticketsystem.auth.service.refreshtoken;

import com.ticketsystem.auth.entity.RefreshToken;
import com.ticketsystem.auth.enums.TokenType;
import com.ticketsystem.auth.exception.UnauthorizedAuthenticationException;
import com.ticketsystem.auth.jwt.core.JwtService;
import com.ticketsystem.auth.repository.RefreshTokenRepository;
import com.ticketsystem.auth.security.AuthenticatedUser;
import com.ticketsystem.auth.util.TokenHash;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Validates signature, ownership, stored hash, CSRF and session state before rotation. */
@Service
public class RefreshTokenValidator {
    private final JwtService jwtService;
    private final RefreshTokenRepository tokens;
    private final RefreshTokenService refreshTokens;
    private final Clock clock;

    public RefreshTokenValidator(JwtService jwtService, RefreshTokenRepository tokens,
                                 RefreshTokenService refreshTokens, Clock clock) {
        this.jwtService = jwtService;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = UnauthorizedAuthenticationException.class)
    public RefreshToken validate(String rawToken, String csrfCookie, String csrfHeader, boolean logout) {
        Claims claims;
        try {
            claims = jwtService.extractAllClaims(rawToken, TokenType.REFRESH);
        } catch (JwtException | IllegalArgumentException ex) {
            throw invalidRefresh();
        }
        RefreshToken stored = tokens.findByIdForUpdate(UUID.fromString(claims.getId()))
                .orElseThrow(RefreshTokenValidator::invalidRefresh);
        if (!stored.getUser().getId().toString().equals(claims.getSubject())
                || !TokenHash.hashesMatch(stored.getTokenHash(), TokenHash.hash(rawToken))) {
            throw invalidRefresh();
        }
        if (csrfCookie == null || csrfHeader == null || csrfCookie.length() > 128 || csrfHeader.length() > 128
                || !TokenHash.hashesMatch(csrfCookie, csrfHeader)
                || !TokenHash.hashesMatch(stored.getCsrfHash(), TokenHash.hash(csrfHeader))) {
            throw new UnauthorizedAuthenticationException("Refresh CSRF validation failed.");
        }
        if (!logout && !stored.isActiveAt(clock.instant())) {
            refreshTokens.revokeFamily(stored.getFamilyId(), clock.instant());
            throw invalidRefresh();
        }
        return stored;
    }

    @Transactional(readOnly = true)
    public AuthenticatedUser authenticateAccessToken(String rawToken) {
        Claims claims = jwtService.extractAllClaims(rawToken, TokenType.ACCESS);
        RefreshToken session = tokens.findByIdWithUser(UUID.fromString(claims.get("sid", String.class)))
                .filter(token -> token.isActiveAt(clock.instant()))
                .filter(token -> token.getUser().getId().toString().equals(claims.getSubject()))
                .orElseThrow(() -> new BadCredentialsException("Inactive token session"));
        return new AuthenticatedUser(session.getUser().getId(), session.getUser().getRole());
    }

    private static UnauthorizedAuthenticationException invalidRefresh() {
        return new UnauthorizedAuthenticationException("The refresh session is invalid.");
    }
}
