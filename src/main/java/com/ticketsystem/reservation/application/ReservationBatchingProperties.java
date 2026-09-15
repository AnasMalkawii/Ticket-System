package com.ticketsystem.reservation.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional micro-batching for extreme bursts against one event inventory row. */
@ConfigurationProperties(prefix = "ticketing.reservation-batching")
public record ReservationBatchingProperties(boolean enabled,
                                            Duration window,
                                            int maxBatchSize) {

    public ReservationBatchingProperties {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("reservation-batching.window must be positive");
        }
        if (maxBatchSize < 1) {
            throw new IllegalArgumentException("reservation-batching.max-batch-size must be positive");
        }
    }
}
