package com.ticketsystem.reservation.application;

import java.util.UUID;

/**
 * Everything the reserve transaction needs, already extracted from the HTTP layer.
 *
 * @param requestFingerprint deprecated caller value; deliberately ignored because the service
 *                           computes the canonical fingerprint from trusted command fields
 */
public record ReserveTicketsCommand(UUID eventId,
                                    UUID userId,
                                    int quantity,
                                    String idempotencyKey,
                                    String requestFingerprint,
                                    String correlationId) {

    /** Preferred form: the application service derives the trusted fingerprint itself. */
    public ReserveTicketsCommand(UUID eventId,
                                 UUID userId,
                                 int quantity,
                                 String idempotencyKey,
                                 String correlationId) {
        this(eventId, userId, quantity, idempotencyKey, null, correlationId);
    }
}
