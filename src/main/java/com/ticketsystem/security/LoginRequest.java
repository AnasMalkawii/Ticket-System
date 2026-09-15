package com.ticketsystem.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credential request matching the public OpenAPI login schema. */
public record LoginRequest(
        @NotBlank @Size(min = 1, max = 64) String username,
        @NotBlank @Size(min = 8, max = 128) String password) {
}
