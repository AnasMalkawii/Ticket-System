package com.ticketsystem.security;

import java.util.Optional;

/** Roles carried by access tokens and enforced at the HTTP boundary. */
public enum Role {
    USER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

    /** Parses only the exact public claim values; tokens do not get case-folded privileges. */
    public static Optional<Role> fromClaim(Object claim) {
        if (!(claim instanceof String value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Role.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
