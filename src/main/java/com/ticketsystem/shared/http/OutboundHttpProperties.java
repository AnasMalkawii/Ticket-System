package com.ticketsystem.shared.http;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit deadlines for outbound HTTP calls; callers never inherit library defaults. */
@ConfigurationProperties(prefix = "ticketing.outbound-http")
public record OutboundHttpProperties(Duration connectTimeout, Duration requestTimeout) {

    public OutboundHttpProperties {
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(requestTimeout, "request-timeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("outbound-http." + name + " must be positive");
        }
    }
}
