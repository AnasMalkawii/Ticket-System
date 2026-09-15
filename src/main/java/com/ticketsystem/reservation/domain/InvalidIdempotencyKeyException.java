package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Raised when an idempotency key does not satisfy the public API contract. */
public class InvalidIdempotencyKeyException extends DomainException {

    public InvalidIdempotencyKeyException() {
        super(ErrorCode.VALIDATION_ERROR,
                "Idempotency-Key must be 8-128 characters and contain only letters, digits, '.', '_', ':', or '-'.");
    }
}
