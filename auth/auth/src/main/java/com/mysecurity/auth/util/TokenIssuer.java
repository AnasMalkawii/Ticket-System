package com.mysecurity.auth.util;

import com.mysecurity.auth.config.CookieProperties;
import com.mysecurity.auth.entity.RefreshToken;
import com.mysecurity.user.entity.User;
import com.mysecurity.auth.enums.TokenType;
import com.mysecurity.auth.jwt.core.JwtService;
import com.mysecurity.auth.repository.RefreshTokenRepository;
import com.mysecurity.auth.service.refreshtoken.RefreshTokenService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TokenIssuer {
    private final JwtService jwtService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final CookieProperties cookieProperties;

    public String issuerTokens(User user, String deviceIp, HttpServletResponse response) {

        RefreshToken refreshTokenEntity = refreshTokenService.createAndSaveRefreshToken(user, deviceIp);

        String refreshToken = jwtService.generateToken(user, refreshTokenEntity.getId(), TokenType.REFRESH);
        String accessToken = jwtService.generateToken(user, refreshTokenEntity.getId(), TokenType.ACCESS);


        refreshTokenEntity.setTokenHash(TokenHash.hash(refreshToken));

        refreshTokenRepository.save(refreshTokenEntity);

        CookieUtil.addRefreshTokenToCookie(response, refreshToken, cookieProperties);

        return accessToken;

    }
}
