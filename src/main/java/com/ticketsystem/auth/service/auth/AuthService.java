package com.ticketsystem.auth.service.auth;

import com.ticketsystem.auth.config.AuthProperties;
import com.ticketsystem.auth.dto.register.RegisterResponse;
import com.ticketsystem.auth.entity.RefreshToken;
import com.ticketsystem.auth.enums.Role;
import com.ticketsystem.auth.exception.UnauthorizedAuthenticationException;
import com.ticketsystem.auth.service.refreshtoken.RefreshTokenService;
import com.ticketsystem.auth.service.refreshtoken.RefreshTokenValidator;
import com.ticketsystem.auth.util.TokenIssuer;
import com.ticketsystem.auth.util.TokenIssuer.IssuedTokens;
import com.ticketsystem.shared.error.InvalidRequestException;
import com.ticketsystem.user.dto.UserResponse;
import com.ticketsystem.user.entity.User;
import com.ticketsystem.user.exception.DuplicateUsernameException;
import com.ticketsystem.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Auth module orchestration, adapted to the ticket API and its persisted identities. */
@Service
public class AuthService {
    private static final int BCRYPT_MAXIMUM_BYTES = 72;
    private static final String DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$E48nxbwy9iQvsYkKValg6ectOZrSx/ZCaZRBcEh5LTZIsUY9mD0uO";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;
    private final RefreshTokenValidator validator;
    private final RefreshTokenService refreshTokens;
    private final AuthenticationAttemptLimiter attempts;
    private final AuthProperties properties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer,
                       RefreshTokenValidator validator, RefreshTokenService refreshTokens,
                       AuthenticationAttemptLimiter attempts, AuthProperties properties, JdbcTemplate jdbc, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.validator = validator;
        this.refreshTokens = refreshTokens;
        this.attempts = attempts;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public RegisterResponse register(String requestedUsername, String password, String clientIp) {
        String username = normalizeUsername(requestedUsername);
        attempts.record(clientIp, "registration:" + username);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAXIMUM_BYTES) {
            throw new InvalidRequestException("password must contain at most 72 UTF-8 bytes");
        }
        String passwordHash = passwordEncoder.encode(password);
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO app_user
                    (id, username, password_hash, role, enabled, failed_login_attempts,
                     created_at, updated_at, version)
                VALUES (?, ?, ?, 'USER', TRUE, 0, ?, ?, 0)
                ON CONFLICT (username) DO NOTHING
                """, id, username, passwordHash, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) throw new DuplicateUsernameException();
        return new RegisterResponse(id, username, Role.USER);
    }

    @Transactional(noRollbackFor = UnauthorizedAuthenticationException.class)
    public IssuedTokens login(String requestedUsername, String password, String clientIp, String userAgent) {
        String username = normalizeUsername(requestedUsername);
        attempts.record(clientIp, username);
        Instant now = clock.instant();
        User user = users.findByUsernameForUpdate(username).orElse(null);
        if (user == null) {
            safePasswordMatch(password, DUMMY_PASSWORD_HASH);
            throw new UnauthorizedAuthenticationException();
        }
        boolean passwordMatches = safePasswordMatch(password, user.getPassword());
        if (!user.canAuthenticateAt(now) || !passwordMatches) {
            if (user.isEnabled() && !user.isLockedAt(now)) {
                var protection = properties.loginProtection();
                user.recordFailedLogin(now, protection.maxFailedAttempts(), protection.lockDuration());
            }
            throw new UnauthorizedAuthenticationException();
        }
        user.recordSuccessfulLogin(now);
        return tokenIssuer.issueTokens(user, UUID.randomUUID(), clientIp, userAgent, now);
    }

    @Transactional(noRollbackFor = UnauthorizedAuthenticationException.class)
    public IssuedTokens refresh(String refreshToken, String csrfCookie, String csrfHeader,
                                String clientIp, String userAgent) {
        RefreshToken current = validator.validate(refreshToken, csrfCookie, csrfHeader, false);
        Instant now = clock.instant();
        IssuedTokens result = tokenIssuer.issueTokens(
                current.getUser(), current.getFamilyId(), clientIp, userAgent, now);
        current.rotateTo(result.sessionId(), now);
        return result;
    }

    @Transactional(noRollbackFor = UnauthorizedAuthenticationException.class)
    public void logout(String refreshToken, String csrfCookie, String csrfHeader) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        RefreshToken current = validator.validate(refreshToken, csrfCookie, csrfHeader, true);
        refreshTokens.revokeFamily(current.getFamilyId(), clock.instant());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        User user = users.findById(userId).filter(User::isEnabled)
                .orElseThrow(() -> new UnauthorizedAuthenticationException("The authenticated account is unavailable."));
        return new UserResponse(user.getId(), user.getUsername(), user.getRole());
    }

    private boolean safePasswordMatch(String password, String hash) {
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAXIMUM_BYTES) return false;
        try {
            return passwordEncoder.matches(password, hash);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
