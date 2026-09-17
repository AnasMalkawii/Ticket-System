package com.mysecurity.auth.service.refreshtoken;

import com.mysecurity.auth.entity.RefreshToken;
import com.mysecurity.user.entity.User;
import com.mysecurity.auth.enums.TokenType;
import com.mysecurity.auth.jwt.core.JwtService;
import com.mysecurity.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public RefreshToken createAndSaveRefreshToken(User user, String deviceIp) {
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .deviceIp(deviceIp)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusMillis(jwtService.extractTokenExpiration(TokenType.REFRESH)))
                .build();

        return refreshTokenRepository.save(refreshToken);

    }

    public boolean isRevoked(Long id){
        return refreshTokenRepository.findById(id).map(RefreshToken::isRevoked).orElse(true);
    }

    public void revokeRefreshToken(Long id) {
        refreshTokenRepository.findById(id).ifPresent( token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    /**
     * Revokes every active refresh token for a user in its OWN transaction.
     * This runs on reuse detection: the caller then throws to reject the request, and
     * REQUIRES_NEW ensures the revocation is committed independently and is NOT rolled
     * back with the caller's failing (rejected) transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId);
    }

}
