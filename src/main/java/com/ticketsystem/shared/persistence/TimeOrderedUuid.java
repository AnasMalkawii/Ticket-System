package com.ticketsystem.shared.persistence;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Generates RFC 9562 version-7 UUIDs without a database round trip. */
public final class TimeOrderedUuid {

    private TimeOrderedUuid() {
    }

    public static UUID next() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long unixMillis = System.currentTimeMillis() & 0x0000FFFFFFFFFFFFL;
        long mostSignificant = (unixMillis << 16)
                | 0x7000L
                | random.nextLong(0x1000L);
        long leastSignificant = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL)
                | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
