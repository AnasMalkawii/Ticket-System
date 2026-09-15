package com.ticketsystem.order.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deadline for the payment boundary plus deterministic mock latency used by failure drills. */
@ConfigurationProperties(prefix = "ticketing.payment")
public record PaymentProperties(Duration timeout, Duration mockTimeoutDelay) {

    public PaymentProperties {
        requirePositive(timeout, "timeout");
        requirePositive(mockTimeoutDelay, "mock-timeout-delay");
        if (mockTimeoutDelay.compareTo(timeout) <= 0) {
            throw new IllegalArgumentException(
                    "payment.mock-timeout-delay must be greater than payment.timeout");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("payment." + name + " must be positive");
        }
    }
}
