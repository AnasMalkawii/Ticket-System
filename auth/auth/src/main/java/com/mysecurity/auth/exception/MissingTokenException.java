package com.mysecurity.auth.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class MissingTokenException extends ApiException {
    public MissingTokenException() {
        super("Token Missing", HttpStatus.UNAUTHORIZED);
    }
}
