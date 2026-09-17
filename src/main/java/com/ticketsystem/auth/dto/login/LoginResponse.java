package com.ticketsystem.auth.dto.login;

import com.ticketsystem.auth.util.TokenIssuer.IssuedTokens;
import com.ticketsystem.auth.enums.Role;

/** Short-lived access token response. Refresh and CSRF credentials are cookie-only. */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, Role role) {

    static final String BEARER_TOKEN_TYPE = "Bearer";

    public static LoginResponse from(IssuedTokens result) {
        return new LoginResponse(
                result.accessToken(), BEARER_TOKEN_TYPE, result.expiresIn(), result.role());
    }
}
