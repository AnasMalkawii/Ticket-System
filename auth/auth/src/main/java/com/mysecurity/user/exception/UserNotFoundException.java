package com.mysecurity.user.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class UserNotFoundException extends ApiException {
    public UserNotFoundException() {
        super("User Not Found!", HttpStatus.NOT_FOUND);
    }
}
