package com.ticketsystem.inventory.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

/** Raised when a hold is requested for more tickets than remain available. */
public class InsufficientInventoryException extends DomainException {

    private final UUID eventId;
    private final int requested;
    private final int available;

    public InsufficientInventoryException(UUID eventId, int requested, int available) {
        super(ErrorCode.SOLD_OUT,
                "Event %s has %d tickets available, %d requested".formatted(eventId, available, requested));
        this.eventId = eventId;
        this.requested = requested;
        this.available = available;
    }

    public UUID getEventId() {
        return eventId;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
