package com.mysecurity.auth.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidToken extends ApiException {
    public InvalidToken() {
        super("Invalid Token", HttpStatus.UNAUTHORIZED);
    }
}
