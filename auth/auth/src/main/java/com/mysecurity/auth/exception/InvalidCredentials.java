package com.mysecurity.auth.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCredentials extends ApiException {
    public InvalidCredentials() {
        super("Invalid Credentials", HttpStatus.UNAUTHORIZED);
    }
}
