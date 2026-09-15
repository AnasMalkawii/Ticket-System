package com.ticketsystem.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Authentication and token-issuance settings bound from {@code ticketing.security}. */
@Validated
@ConfigurationProperties(prefix = "ticketing.security")
public record SecurityProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration tokenTtl,
        @NotBlank String signingKey,
        @Valid @NotNull Account user,
        @Valid @NotNull Account admin) {

    private static final int MINIMUM_HS256_KEY_BYTES = 32;

    public SecurityProperties {
        if (tokenTtl == null || tokenTtl.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("ticketing.security.token-ttl must be at least 1 second");
        }
        if (signingKey == null
                || signingKey.isBlank()
                || signingKey.getBytes(StandardCharsets.UTF_8).length < MINIMUM_HS256_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "ticketing.security.signing-key must contain at least 32 UTF-8 bytes");
        }
        if (user == null || admin == null) {
            throw new IllegalArgumentException("Both USER and ADMIN accounts must be configured");
        }
        if (Objects.equals(user.id(), admin.id())) {
            throw new IllegalArgumentException("Configured USER and ADMIN ids must be distinct");
        }
        if (Objects.equals(user.username(), admin.username())) {
            throw new IllegalArgumentException("Configured USER and ADMIN usernames must be distinct");
        }
    }

    public List<ConfiguredAccount> accounts() {
        return List.of(
                new ConfiguredAccount(user.id(), user.username(), user.passwordHash(), Role.USER),
                new ConfiguredAccount(admin.id(), admin.username(), admin.passwordHash(), Role.ADMIN));
    }

    public Optional<ConfiguredAccount> findAccountByUsername(String username) {
        return accounts().stream()
                .filter(account -> account.username().equals(username))
                .findFirst();
    }

    /** Password hashes use Spring Security's delegating format, for example {@code {bcrypt}...}. */
    public record Account(
            @NotNull UUID id,
            @NotBlank @Size(min = 1, max = 64) String username,
            @NotBlank @Size(max = 255) String passwordHash) {

        public Account {
            if (passwordHash != null
                    && (!passwordHash.startsWith("{") || passwordHash.indexOf('}') < 2)) {
                throw new IllegalArgumentException(
                        "Configured password hashes must include an encoder id such as {bcrypt}");
            }
        }
    }

    /** Account configuration resolved together with its non-configurable role. */
    public record ConfiguredAccount(UUID id, String username, String passwordHash, Role role) {
    }
}
