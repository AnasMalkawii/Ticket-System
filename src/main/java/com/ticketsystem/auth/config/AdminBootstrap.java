package com.ticketsystem.auth.config;

import com.ticketsystem.auth.enums.Role;

import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Optional, idempotent first-deployment administrator bootstrap using an encoded hash. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private final AuthProperties properties;
    private final JdbcTemplate jdbc;

    public AdminBootstrap(AuthProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        AuthProperties.BootstrapAdmin admin = properties.bootstrapAdmin();
        if (!admin.configured()) {
            return;
        }
        int inserted = jdbc.update("""
                INSERT INTO app_user
                    (id, username, password_hash, role, enabled, failed_login_attempts,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'ADMIN', TRUE, 0, now(), now(), 0)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), admin.username(), admin.passwordHash());
        if (inserted == 0) {
            String existingRole = jdbc.query(
                    "SELECT role FROM app_user WHERE username = ?",
                    result -> result.next() ? result.getString(1) : null,
                    admin.username());
            if (!Role.ADMIN.name().equals(existingRole)) {
                throw new IllegalStateException(
                        "Bootstrap administrator username belongs to a non-admin account");
            }
        }
    }
}
