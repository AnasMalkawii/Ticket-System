package com.ticketsystem.auth.exception;



import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Generic authentication failure that deliberately does not reveal which credential failed. */
public final class UnauthorizedAuthenticationException extends DomainException {

    public UnauthorizedAuthenticationException() {
        super(ErrorCode.UNAUTHORIZED, "Invalid username or password.");
    }

    public UnauthorizedAuthenticationException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
