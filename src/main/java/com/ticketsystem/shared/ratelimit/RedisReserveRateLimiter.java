package com.ticketsystem.shared.ratelimit;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Cross-replica fixed-window limiter backed by one atomic Redis Lua operation.
 *
 * <p>Only Redis access failures fail open. A malformed script response or configuration bug
 * remains a server fault rather than silently disabling protection.
 */
@Component
@ConditionalOnProperty(prefix = "ticketing.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisReserveRateLimiter implements ReserveRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisReserveRateLimiter.class);
    private static final String KEY_PREFIX = "ticketing:rate-limit:reserve:";
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local userCount = redis.call('INCR', KEYS[1])
            if userCount == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[3])
            end

            local ipCount = redis.call('INCR', KEYS[2])
            if ipCount == 1 then
              redis.call('PEXPIRE', KEYS[2], ARGV[3])
            end

            local allowed = 1
            local retryMillis = 0
            if userCount > tonumber(ARGV[1]) then
              allowed = 0
              retryMillis = math.max(retryMillis, redis.call('PTTL', KEYS[1]))
            end
            if ipCount > tonumber(ARGV[2]) then
              allowed = 0
              retryMillis = math.max(retryMillis, redis.call('PTTL', KEYS[2]))
            end
            if allowed == 0 and retryMillis < 1 then
              retryMillis = tonumber(ARGV[3])
            end
            return tostring(allowed) .. ':' .. tostring(retryMillis)
            """, String.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;

    public RedisReserveRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public void check(UUID userId, String clientIp) {
        Objects.requireNonNull(userId, "userId");
        if (clientIp == null || clientIp.isBlank()) {
            throw new IllegalArgumentException("clientIp is required for rate limiting");
        }

        Duration window = properties.getWindow();
        long windowMillis = window.toMillis();
        validateConfiguration(windowMillis);

        String result;
        try {
            result = redis.execute(CONSUME_SCRIPT,
                    List.of(userKey(userId), ipKey(clientIp)),
                    Integer.toString(properties.getPerUser()),
                    Integer.toString(properties.getPerIp()),
                    Long.toString(windowMillis));
        } catch (DataAccessResourceFailureException | QueryTimeoutException redisUnavailable) {
            // Redis is never inventory authority. Losing abuse protection is safer than
            // turning a cache outage into a booking outage (F-09).
            log.warn("Redis rate limiter unavailable; allowing reservation attempt", redisUnavailable);
            return;
        }

        Decision decision = parse(result);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(toRetryAfterSeconds(decision.retryMillis()));
        }
    }

    private void validateConfiguration(long windowMillis) {
        if (windowMillis < 1 || properties.getPerUser() < 1 || properties.getPerIp() < 1) {
            throw new IllegalStateException("Rate-limit window and limits must be positive");
        }
    }

    private Decision parse(String result) {
        if (result == null) {
            throw new IllegalStateException("Redis rate-limit script returned no result");
        }
        String[] fields = result.split(":", 2);
        if (fields.length != 2 || !(fields[0].equals("0") || fields[0].equals("1"))) {
            throw new IllegalStateException("Malformed Redis rate-limit result: " + result);
        }
        try {
            return new Decision(fields[0].equals("1"), Long.parseLong(fields[1]));
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException("Malformed Redis rate-limit result: " + result, malformed);
        }
    }

    private int toRetryAfterSeconds(long retryMillis) {
        long seconds = Math.max(1, Math.ceilDiv(retryMillis, 1_000));
        return Math.toIntExact(Math.min(seconds, Integer.MAX_VALUE));
    }

    private String userKey(UUID userId) {
        return KEY_PREFIX + "user:" + userId;
    }

    private String ipKey(String clientIp) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(clientIp.getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + "ip:" + encoded;
    }

    private record Decision(boolean allowed, long retryMillis) {
    }
}
