package com.mysecurity.user.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class DuplicateUsernameException extends ApiException {
    public DuplicateUsernameException() {
        super("Username already exists", HttpStatus.CONFLICT);
    }
}
