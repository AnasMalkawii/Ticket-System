package com.ticketsystem.security;

/** Issued bearer token matching the public OpenAPI login response schema. */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, Role role) {

    public static final String BEARER_TOKEN_TYPE = "Bearer";

    static LoginResponse from(LoginService.LoginResult result) {
        return new LoginResponse(
                result.accessToken(), BEARER_TOKEN_TYPE, result.expiresIn(), result.role());
    }
}
