package com.mysecurity.auth.jwt.core;

import com.mysecurity.auth.enums.TokenType;
import com.mysecurity.auth.jwt.strategy.AccessTokenStrategy;
import com.mysecurity.auth.jwt.strategy.RefreshTokenStrategy;
import com.mysecurity.auth.jwt.strategy.TokenStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class TokenFactory {
    private final AccessTokenStrategy accessTokenStrategy;
    private final RefreshTokenStrategy refreshTokenStrategy;

    public TokenStrategy getTokenStrategy(TokenType tokenType) {
        return switch(tokenType){
            case ACCESS -> accessTokenStrategy;
            case REFRESH -> refreshTokenStrategy;
        };
    }
}
