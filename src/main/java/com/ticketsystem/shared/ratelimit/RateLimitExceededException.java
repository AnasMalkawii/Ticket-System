package com.ticketsystem.shared.ratelimit;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** A deliberate 429 protection response, distinct from ticket availability outcomes. */
public class RateLimitExceededException extends DomainException {

    private final int retryAfterSeconds;

    public RateLimitExceededException(int retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED,
                "Too many reservation attempts. Retry after %d seconds."
                        .formatted(retryAfterSeconds));
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
