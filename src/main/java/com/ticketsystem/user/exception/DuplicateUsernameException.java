package com.ticketsystem.user.exception;



import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

public final class DuplicateUsernameException extends DomainException {

    public DuplicateUsernameException() {
        super(ErrorCode.USERNAME_UNAVAILABLE, "That username is unavailable.");
    }
}
