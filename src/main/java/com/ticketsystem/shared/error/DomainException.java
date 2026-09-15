package com.ticketsystem.shared.error;

/**
 * Base type for business-rule violations. Carries an {@link ErrorCode} so the API layer can
 * map any domain failure to the documented problem+json contract without a growing
 * chain of instanceof checks.
 */
public abstract class DomainException extends RuntimeException {

    private final ErrorCode errorCode;

    protected DomainException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
