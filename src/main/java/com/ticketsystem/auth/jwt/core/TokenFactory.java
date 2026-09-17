package com.ticketsystem.auth.jwt.core;

import com.ticketsystem.auth.enums.TokenType;
import com.ticketsystem.auth.jwt.strategy.AccessTokenStrategy;
import com.ticketsystem.auth.jwt.strategy.RefreshTokenStrategy;
import com.ticketsystem.auth.jwt.strategy.TokenStrategy;
import org.springframework.stereotype.Component;

@Component
public final class TokenFactory {
    private final AccessTokenStrategy access;
    private final RefreshTokenStrategy refresh;

    public TokenFactory(AccessTokenStrategy access, RefreshTokenStrategy refresh) {
        this.access = access;
        this.refresh = refresh;
    }

    public TokenStrategy getTokenStrategy(TokenType type) {
        return switch (type) { case ACCESS -> access; case REFRESH -> refresh; };
    }
}
