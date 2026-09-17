package com.ticketsystem.auth.jwt.strategy;

import com.ticketsystem.auth.config.AuthProperties;
import com.ticketsystem.auth.enums.TokenType;
import com.ticketsystem.auth.jwt.core.JwtKeyProvider;
import java.time.Clock;
import org.springframework.stereotype.Component;

@Component
public final class AccessTokenStrategy extends SignedTokenStrategy {
    public AccessTokenStrategy(JwtKeyProvider keys, AuthProperties properties, Clock clock) {
        super(keys.getAccessKey(), properties, TokenType.ACCESS, properties.accessTokenTtl(), clock);
    }
}
