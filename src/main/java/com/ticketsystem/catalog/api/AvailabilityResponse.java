package com.ticketsystem.catalog.api;

import com.ticketsystem.inventory.application.InventorySnapshot;
import java.time.Instant;
import java.util.UUID;

/** Explicitly advisory inventory snapshot; reservation transactions never consume it. */
public record AvailabilityResponse(UUID eventId,
                                   int total,
                                   int available,
                                   boolean advisory,
                                   Instant asOf) {

    public static AvailabilityResponse from(InventorySnapshot snapshot) {
        return new AvailabilityResponse(snapshot.eventId(), snapshot.total(),
                snapshot.available(), true, snapshot.asOf());
    }
}
