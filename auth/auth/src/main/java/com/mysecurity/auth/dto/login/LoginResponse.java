package com.mysecurity.auth.dto.login;

public record LoginResponse(
    String message,
    String accessToken
) {
}
