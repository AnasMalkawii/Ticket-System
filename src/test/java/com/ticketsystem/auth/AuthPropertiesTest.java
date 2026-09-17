package com.ticketsystem.auth;

import com.ticketsystem.auth.config.AuthProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

    private static final String VALID_HASH =
            "{bcrypt}$2a$10$E48nxbwy9iQvsYkKValg6ectOZrSx/ZCaZRBcEh5LTZIsUY9mD0uO";

    @Test
    void acceptsAWellFormedBootstrapAdministratorHash() {
        AuthProperties.BootstrapAdmin admin =
                new AuthProperties.BootstrapAdmin("First.Admin", VALID_HASH);

        assertThat(admin.configured()).isTrue();
        assertThat(admin.username()).isEqualTo("first.admin");
    }

    @Test
    void rejectsWeakOrMalformedBootstrapAdministratorHashes() {
        assertThatThrownBy(() -> new AuthProperties.BootstrapAdmin(
                        "first-admin", VALID_HASH.replace("$10$", "$04$")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties.BootstrapAdmin(
                        "first-admin", "{bcrypt}not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInsecurePrefixedOrCollidingCookieNames() {
        assertThatThrownBy(() -> new AuthProperties.Cookie("__Host-refresh", "csrf", "/", false, "Lax"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties.Cookie("same", "same", "/", true, "Strict"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties.Cookie("refresh", "csrf", "/", false, "None"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
