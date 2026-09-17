package com.ticketsystem.auth.config;



import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Base64;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Security settings. Secrets are supplied at runtime and are never committed. */
@Validated
@ConfigurationProperties(prefix = "ticketing.security")
public record AuthProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl,
        @NotNull Duration clockSkew,
        @NotBlank String accessSecret,
        @NotBlank String refreshSecret,
        @Valid @NotNull Cookie cookie,
        @Valid @NotNull LoginProtection loginProtection,
        @Valid @NotNull BootstrapAdmin bootstrapAdmin,
        List<String> allowedOrigins) {

    private static final int MINIMUM_HS256_KEY_BYTES = 32;

    public AuthProperties {
        requirePositive(accessTokenTtl, "access-token-ttl");
        requirePositive(refreshTokenTtl, "refresh-token-ttl");
        if (accessTokenTtl != null && refreshTokenTtl != null
                && refreshTokenTtl.compareTo(accessTokenTtl) <= 0) {
            throw new IllegalArgumentException(
                    "ticketing.security.refresh-token-ttl must exceed access-token-ttl");
        }
        if (clockSkew == null || clockSkew.isNegative()
                || clockSkew.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException(
                    "ticketing.security.clock-skew must be between 0 and 5 minutes");
        }
        byte[] accessKey = decodeSecret(accessSecret, "access-secret");
        byte[] refreshKey = decodeSecret(refreshSecret, "refresh-secret");
        if (MessageDigest.isEqual(accessKey, refreshKey)) {
            throw new IllegalArgumentException(
                    "Access and refresh signing secrets must be different");
        }
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private static byte[] decodeSecret(String value, String property) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value == null ? "" : value);
            if (decoded.length >= MINIMUM_HS256_KEY_BYTES) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Never include the supplied secret in a configuration error.
        }
        throw new IllegalArgumentException(
                "ticketing.security." + property + " must be Base64 encoding at least 32 random bytes");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                    "ticketing.security." + property + " must be at least one second");
        }
    }

    public record Cookie(
            @NotBlank String refreshName,
            @NotBlank String csrfName,
            @NotBlank String path,
            boolean secure,
            @NotBlank String sameSite) {

        public Cookie {
            if (refreshName == null || csrfName == null || refreshName.equals(csrfName)
                    || !refreshName.matches("[A-Za-z0-9_-]+") || !csrfName.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("Authentication cookie names must be valid and distinct");
            }
            if (!secure && (refreshName.startsWith("__Host-") || csrfName.startsWith("__Host-")
                    || refreshName.startsWith("__Secure-") || csrfName.startsWith("__Secure-"))) {
                throw new IllegalArgumentException("Prefixed authentication cookies must be Secure");
            }
            if (path == null || !"/".equals(path)) {
                throw new IllegalArgumentException("Authentication cookie path must be /");
            }
            if (sameSite == null || !(sameSite.equals("Strict")
                    || sameSite.equals("Lax") || sameSite.equals("None"))) {
                throw new IllegalArgumentException("Cookie SameSite must be Strict, Lax, or None");
            }
            if ("None".equals(sameSite) && !secure) {
                throw new IllegalArgumentException("SameSite=None cookies must be Secure");
            }
        }
    }

    public record LoginProtection(
            int maxFailedAttempts,
            @NotNull Duration lockDuration,
            @NotNull Duration rateWindow,
            int maxAttemptsPerIp,
            int maxAttemptsPerUsername) {

        public LoginProtection {
            if (maxFailedAttempts < 1 || maxAttemptsPerIp < 1 || maxAttemptsPerUsername < 1) {
                throw new IllegalArgumentException("Authentication attempt limits must be positive");
            }
            requirePositive(lockDuration, "login-protection.lock-duration");
            requirePositive(rateWindow, "login-protection.rate-window");
        }
    }

    public record BootstrapAdmin(String username, String passwordHash) {

        private static final Pattern BCRYPT_HASH = Pattern.compile(
                "^\\{bcrypt}\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");

        public BootstrapAdmin {
            username = username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
            passwordHash = passwordHash == null ? "" : passwordHash.trim();
            if (username.isEmpty() != passwordHash.isEmpty()) {
                throw new IllegalArgumentException(
                        "Bootstrap admin username and password hash must be supplied together");
            }
            if (!username.isEmpty() && !username.matches("^[a-z0-9._-]{3,64}$")) {
                throw new IllegalArgumentException("Bootstrap admin username is invalid");
            }
            if (!passwordHash.isEmpty() || !username.isEmpty()) {
                Matcher bcrypt = BCRYPT_HASH.matcher(passwordHash);
                if (!bcrypt.matches() || Integer.parseInt(bcrypt.group(1)) < 10) {
                    throw new IllegalArgumentException(
                            "Bootstrap admin password must be a valid {bcrypt} hash "
                                    + "with work factor 10 or greater");
                }
            }
        }

        public boolean configured() {
            return !username.isEmpty();
        }
    }
}
