package com.ticketsystem.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** Validated identity extracted from a bearer JWT for application-service ownership checks. */
public record AuthenticatedUser(UUID userId, Role role) {

    public static AuthenticatedUser from(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new UnauthorizedAuthenticationException("A valid bearer token is required.");
        }
        return from(jwt);
    }

    public static AuthenticatedUser from(Jwt jwt) {
        if (jwt == null) {
            throw new UnauthorizedAuthenticationException("A valid bearer token is required.");
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new UnauthorizedAuthenticationException("The bearer token subject is invalid.");
        }
        Role role = Role.fromClaim(jwt.getClaim(JwtRoleAuthoritiesConverter.ROLE_CLAIM))
                .orElseThrow(() -> new UnauthorizedAuthenticationException(
                        "The bearer token role is invalid."));
        return new AuthenticatedUser(userId, role);
    }
}
