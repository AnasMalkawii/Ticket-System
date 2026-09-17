package com.mysecurity.auth.exception;

import com.mysecurity.common.ApiException;
import org.springframework.http.HttpStatus;

public class RevokedToken extends ApiException {
    public RevokedToken() {
        super("Token is revoked", HttpStatus.UNAUTHORIZED);
    }
}
