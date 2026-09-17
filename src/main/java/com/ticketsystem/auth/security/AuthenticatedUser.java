package com.ticketsystem.auth.security;

import com.ticketsystem.auth.enums.Role;
import com.ticketsystem.auth.exception.UnauthorizedAuthenticationException;
import java.security.Principal;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/** Small immutable principal shared with booking services; never carries password/token data. */
public record AuthenticatedUser(UUID userId, Role role) implements Principal {
    @Override
    public String getName() { return userId.toString(); }

    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new UnauthorizedAuthenticationException("A valid bearer token is required.");
        }
        return user;
    }
}
