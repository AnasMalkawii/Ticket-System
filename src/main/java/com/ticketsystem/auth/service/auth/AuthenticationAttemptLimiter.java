package com.ticketsystem.auth.service.auth;

import com.ticketsystem.auth.config.AuthProperties;

import com.ticketsystem.shared.ratelimit.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Bounded per-process pre-authentication throttle; account locks provide replica-wide defense. */
@Component
final class AuthenticationAttemptLimiter {

    private static final int MAXIMUM_TRACKED_KEYS = 20_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AuthProperties properties;
    private final Clock clock;

    AuthenticationAttemptLimiter(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    synchronized void record(String clientIp, String username) {
        AuthProperties.LoginProtection limits = properties.loginProtection();
        Instant now = clock.instant();
        check("ip:" + safe(clientIp), limits.maxAttemptsPerIp(), limits.rateWindow(), now);
        check("user:" + safe(username), limits.maxAttemptsPerUsername(), limits.rateWindow(), now);
        if (windows.size() > MAXIMUM_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
        }
    }

    private void check(String key, int limit, Duration duration, Instant now) {
        if (!windows.containsKey(key) && windows.size() >= MAXIMUM_TRACKED_KEYS) {
            windows.entrySet().removeIf(entry -> entry.getValue().expiredAt(now));
            if (windows.size() >= MAXIMUM_TRACKED_KEYS) {
                throw new RateLimitExceededException(60, "Too many authentication attempts.");
            }
        }
        Window result = windows.compute(key, (ignored, current) -> {
            if (current == null || current.expiredAt(now)) {
                return new Window(now.plus(duration), 1);
            }
            return new Window(current.expiresAt(), current.count() + 1);
        });
        if (result.count() > limit) {
            long retryAfter = Math.max(1, Duration.between(now, result.expiresAt()).toSeconds());
            throw new RateLimitExceededException(
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, retryAfter)),
                    "Too many authentication attempts.");
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private record Window(Instant expiresAt, int count) {
        boolean expiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }
}
