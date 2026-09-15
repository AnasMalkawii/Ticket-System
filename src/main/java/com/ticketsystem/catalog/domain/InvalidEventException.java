package com.ticketsystem.catalog.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Boundary-independent validation for event creation and metadata changes. */
public class InvalidEventException extends DomainException {

    public InvalidEventException(String detail) {
        super(ErrorCode.VALIDATION_ERROR, detail);
    }
}
