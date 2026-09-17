package com.mysecurity.user.dto;

import com.mysecurity.auth.enums.Role;

public record UserResponse(
        String username,
        Role role
) {
}
