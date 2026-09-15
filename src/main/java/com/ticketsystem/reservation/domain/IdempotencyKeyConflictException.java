package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Raised when one idempotency key is reused for a different logical request. */
public class IdempotencyKeyConflictException extends DomainException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                "Idempotency-Key '%s' was already used with a different request."
                        .formatted(idempotencyKey));
    }
}
