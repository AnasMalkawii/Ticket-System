package com.ticketsystem.inventory.application;

import com.ticketsystem.inventory.domain.TicketInventory;
import com.ticketsystem.inventory.repository.TicketInventoryRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalog-facing inventory boundary.
 *
 * <p>Reads are advisory. Mutations require an existing caller-owned database transaction;
 * Redis is never consulted and this service is never used to authorize a reservation.
 */
@Service
public class InventoryCatalogService {

    private final TicketInventoryRepository inventoryRepository;

    public InventoryCatalogService(TicketInventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Transactional(readOnly = true)
    public Optional<InventorySnapshot> findAdvisorySnapshot(UUID eventId) {
        return inventoryRepository.findById(eventId).map(InventorySnapshot::from);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InventorySnapshot create(UUID eventId, int totalTickets, Instant now) {
        return InventorySnapshot.from(
                inventoryRepository.save(TicketInventory.of(eventId, totalTickets, now)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public InventorySnapshot addTickets(UUID eventId, int quantity, Instant now) {
        TicketInventory inventory = inventoryRepository.findWithLockByEventId(eventId)
                .orElseThrow(() -> new IllegalStateException(
                        "Inventory row is missing for event " + eventId));
        inventory.addTickets(quantity, now);
        return InventorySnapshot.from(inventory);
    }
}
