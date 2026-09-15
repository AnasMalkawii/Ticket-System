package com.ticketsystem.inventory.application;

import com.ticketsystem.inventory.domain.TicketInventory;
import java.time.Instant;
import java.util.UUID;

/** Immutable, non-authoritative read model safe to expose to the catalog API and Redis. */
public record InventorySnapshot(UUID eventId,
                                int total,
                                int available,
                                Instant asOf) {

    static InventorySnapshot from(TicketInventory inventory) {
        return new InventorySnapshot(inventory.getEventId(), inventory.getTotal(),
                inventory.getAvailable(), inventory.getUpdatedAt());
    }
}
