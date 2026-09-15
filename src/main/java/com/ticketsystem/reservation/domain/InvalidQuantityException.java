package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/**
 * Quantity outside the configured per-request bounds. Distinct from
 * {@code SOLD_OUT}: the request could never be valid, regardless of how many tickets remain.
 */
public class InvalidQuantityException extends DomainException {

    public InvalidQuantityException(int requested, int max) {
        super(ErrorCode.INVALID_QUANTITY,
                "Quantity must be between 1 and %d, was %d".formatted(max, requested));
    }
}
