package com.ticketsystem.messaging.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded publisher settings; consumer retry settings live under spring.rabbitmq.listener. */
@ConfigurationProperties("ticketing.messaging")
public record MessagingProperties(boolean enabled,
                                  boolean publisherSchedulingEnabled,
                                  Duration publisherInterval,
                                  int batchSize,
                                  Duration confirmTimeout,
                                  Duration initialRetryDelay,
                                  Duration maxRetryDelay) {

    public MessagingProperties {
        requirePositive(publisherInterval, "publisherInterval");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        requirePositive(confirmTimeout, "confirmTimeout");
        requirePositive(initialRetryDelay, "initialRetryDelay");
        requirePositive(maxRetryDelay, "maxRetryDelay");
        if (maxRetryDelay.compareTo(initialRetryDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxRetryDelay must be greater than or equal to initialRetryDelay");
        }
    }

    /** 250 ms, 500 ms, 1 s, ... capped at the configured maximum without overflow. */
    public Duration retryDelayAfter(int failedAttempts) {
        Duration delay = initialRetryDelay;
        for (int attempt = 1; attempt < failedAttempts && delay.compareTo(maxRetryDelay) < 0;
                attempt++) {
            Duration doubled = delay.multipliedBy(2);
            delay = doubled.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : doubled;
        }
        return delay;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
