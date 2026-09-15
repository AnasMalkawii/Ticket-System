package com.ticketsystem.reservation.application;

import com.ticketsystem.reservation.domain.IdempotencyKeyRequiredException;
import com.ticketsystem.reservation.domain.InvalidIdempotencyKeyException;
import java.util.regex.Pattern;

/** The public and service-layer validation policy for reservation idempotency keys. */
public final class IdempotencyKeyPolicy {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private IdempotencyKeyPolicy() {
    }

    public static void validate(String key) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
