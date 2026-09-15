package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Raised when a concurrent request still owns the same idempotency key after a bounded wait. */
public class IdempotencyInProgressException extends DomainException {

    private final int retryAfterSeconds;

    public IdempotencyInProgressException(int retryAfterSeconds) {
        super(ErrorCode.IDEMPOTENCY_IN_PROGRESS,
                "An identical request is still being processed. Retry after %d seconds."
                        .formatted(retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
