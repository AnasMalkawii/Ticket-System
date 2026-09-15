package com.ticketsystem.reservation.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Canonical hash of a reserve request.
 *
 * <p>Stored alongside the idempotency key so a retry can be checked for consistency: the same
 * key with the same payload is an honest retry and is replayed, while the same key with a
 * different payload is a client bug and is rejected with
 * {@code 409 IDEMPOTENCY_KEY_CONFLICT}. It is captured with every reservation so both a
 * later retry and a simultaneous duplicate can be classified after the database arbitrates.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(UUID eventId, UUID userId, int quantity) {
        String canonical = "%s|%s|%d".formatted(eventId, userId, quantity);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
