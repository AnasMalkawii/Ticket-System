package com.ticketsystem.shared.error;

/** Boundary input that is structurally valid HTTP but violates the public API contract. */
public class InvalidRequestException extends DomainException {

    public InvalidRequestException(String detail) {
        super(ErrorCode.VALIDATION_ERROR, detail);
    }
}
