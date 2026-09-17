package com.ticketsystem.auth.dto.login;



import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login input is deliberately bounded before any password hash work occurs. */
public record LoginRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(max = 128) String password) {
}
