package com.ticketsystem.auth.dto.register;

import com.ticketsystem.auth.enums.Role;

import java.util.UUID;

public record RegisterResponse(UUID id, String username, Role role) {
}
