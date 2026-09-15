package com.ticketsystem.shared.config;

import com.ticketsystem.reservation.application.InventoryReservationStrategy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business rules that must stay consistent with {@code docs/architecture.md} section 12.
 *
 * <p>These are configuration, not constants, so the same jar can run a load test with a
 * 10-second TTL and production with three minutes without a rebuild.
 */
@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(Database database,
                                  Reservation reservation,
                                  ExpiryWorker expiryWorker) {

    /** Global PostgreSQL query limits. Hikari applies the same values to every connection. */
    public record Database(Duration lockTimeout, Duration statementTimeout) {
        public Database {
            requirePositive(lockTimeout, "database.lock-timeout");
            requirePositive(statementTimeout, "database.statement-timeout");
        }
    }

    /**
     * @param ttl                    how long a hold survives without confirmation
     * @param idempotencyWaitTimeout maximum time a duplicate waits for the first request
     * @param maxQuantityPerRequest bounds the blast radius of one hostile request
     * @param perUserEventCap       total tickets one user may hold or own for one event (I6)
     */
    public record Reservation(Duration ttl,
                              Duration idempotencyWaitTimeout,
                              int maxQuantityPerRequest,
                              int perUserEventCap,
                              InventoryReservationStrategy inventoryStrategy) {
        public Reservation {
            if (inventoryStrategy == null) {
                throw new IllegalArgumentException(
                        "reservation.inventory-strategy must be configured");
            }
        }
    }

    public record ExpiryWorker(Duration interval, int batchSize) {
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
