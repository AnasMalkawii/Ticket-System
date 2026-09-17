package com.ticketsystem.auth.exception;



import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

/** Raised when an authenticated actor lacks the role or ownership required by an operation. */
public final class ForbiddenOperationException extends DomainException {

    public ForbiddenOperationException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }

    public ForbiddenOperationException(UUID reservationId) {
        this("The authenticated user is not permitted to operate on reservation "
                + reservationId + ".");
    }
}
