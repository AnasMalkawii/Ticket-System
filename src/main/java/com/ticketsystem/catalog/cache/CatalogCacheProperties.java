package com.ticketsystem.catalog.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalized TTLs matching the architecture's 60-second/5-second defaults. */
@ConfigurationProperties(prefix = "ticketing.catalog-cache")
public record CatalogCacheProperties(Duration eventTtl, Duration availabilityTtl) {

    public CatalogCacheProperties {
        requirePositive(eventTtl, "event-ttl");
        requirePositive(availabilityTtl, "availability-ttl");
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "ticketing.catalog-cache.%s must be positive".formatted(property));
        }
    }
}
