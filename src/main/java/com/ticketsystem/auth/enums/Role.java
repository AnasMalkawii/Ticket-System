package com.ticketsystem.auth.enums;



/** Roles loaded from the current database identity and enforced at the HTTP boundary. */
public enum Role {
    USER,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }

}
