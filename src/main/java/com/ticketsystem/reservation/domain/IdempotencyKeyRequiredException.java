package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Raised when a reserve request supplies no usable idempotency key. */
public class IdempotencyKeyRequiredException extends DomainException {

    public IdempotencyKeyRequiredException() {
        super(ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                "The Idempotency-Key header is mandatory for this operation.");
    }
}
