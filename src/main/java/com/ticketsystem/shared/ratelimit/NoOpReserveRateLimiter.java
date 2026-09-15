package com.ticketsystem.shared.ratelimit;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit test/local opt-out; production defaults to the shared Redis limiter. */
@Component
@ConditionalOnProperty(prefix = "ticketing.rate-limit", name = "enabled", havingValue = "false")
public class NoOpReserveRateLimiter implements ReserveRateLimiter {

    @Override
    public void check(UUID userId, String clientIp) {
        // Deliberately disabled by configuration.
    }
}
