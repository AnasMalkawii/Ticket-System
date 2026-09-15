package com.ticketsystem.shared.ratelimit;

import java.util.UUID;

/** Boundary guard for reserve attempts, invoked before the PostgreSQL transaction starts. */
public interface ReserveRateLimiter {

    /**
     * Consumes one attempt from both the user and client-IP windows.
     *
     * @throws RateLimitExceededException when either distributed limit is exhausted
     */
    void check(UUID userId, String clientIp);
}
