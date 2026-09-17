package com.ticketsystem.user.dto;

import com.ticketsystem.auth.enums.Role;

import java.util.UUID;

public record UserResponse(UUID id, String username, Role role) {
}
