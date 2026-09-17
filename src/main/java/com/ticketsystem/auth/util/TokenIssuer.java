package com.ticketsystem.auth.util;

import com.ticketsystem.auth.config.AuthProperties;
import com.ticketsystem.auth.entity.RefreshToken;
import com.ticketsystem.auth.enums.Role;
import com.ticketsystem.auth.enums.TokenType;
import com.ticketsystem.auth.jwt.core.JwtService;
import com.ticketsystem.auth.service.refreshtoken.RefreshTokenService;
import com.ticketsystem.user.entity.User;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Issues the auth module's JWT pair and persists only hashes of browser credentials. */
@Service
public final class TokenIssuer {
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final AuthProperties properties;

    public TokenIssuer(JwtService jwtService, RefreshTokenService refreshTokens, AuthProperties properties) {
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
    }

    public IssuedTokens issueTokens(User user, UUID familyId, String clientIp, String userAgent, Instant now) {
        UUID sessionId = UUID.randomUUID();
        String refresh = jwtService.generateToken(user.getId(), sessionId, TokenType.REFRESH, now);
        String csrf = TokenHash.generate();
        refreshTokens.createAndSaveRefreshToken(RefreshToken.create(
                sessionId, user, familyId, TokenHash.hash(refresh), TokenHash.hash(csrf),
                clientIp, userAgent, now, now.plus(properties.refreshTokenTtl())));
        String access = jwtService.generateToken(user.getId(), sessionId, TokenType.ACCESS, now);
        return new IssuedTokens(sessionId, access, properties.accessTokenTtl().toSeconds(),
                user.getRole(), refresh, csrf);
    }

    public record IssuedTokens(UUID sessionId, String accessToken, long expiresIn,
                               Role role, String refreshToken, String csrfToken) {}
}
