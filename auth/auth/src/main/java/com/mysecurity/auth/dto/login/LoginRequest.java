package com.mysecurity.auth.dto.login;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Username is required")
        String username,

        // Only presence is checked here; the value is verified against the stored
        // hash. Re-imposing registration rules on login would reject valid users
        // whenever the policy changes.
        @NotBlank(message = "Password is required")
        String password
) {
}
