package com.mysecurity.auth.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class ExpiredTokenException extends ApiException {
    public ExpiredTokenException() {
        super("Token is Expired ", HttpStatus.UNAUTHORIZED);
    }
}
