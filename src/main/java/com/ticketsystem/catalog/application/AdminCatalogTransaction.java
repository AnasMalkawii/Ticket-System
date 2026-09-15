package com.ticketsystem.catalog.application;

import com.ticketsystem.catalog.api.CreateEventRequest;
import com.ticketsystem.catalog.api.UpdateEventRequest;
import com.ticketsystem.catalog.domain.Event;
import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.catalog.domain.InvalidCatalogRequestException;
import com.ticketsystem.catalog.repository.EventRepository;
import com.ticketsystem.inventory.application.InventoryCatalogService;
import com.ticketsystem.shared.time.DatabaseTimeProvider;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the database transaction so cache invalidation can happen only after it returns. */
@Service
class AdminCatalogTransaction {

    private final EventRepository eventRepository;
    private final InventoryCatalogService inventoryCatalogService;
    private final DatabaseTimeProvider databaseTime;

    AdminCatalogTransaction(EventRepository eventRepository,
                            InventoryCatalogService inventoryCatalogService,
                            DatabaseTimeProvider databaseTime) {
        this.eventRepository = eventRepository;
        this.inventoryCatalogService = inventoryCatalogService;
        this.databaseTime = databaseTime;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Event create(CreateEventRequest request) {
        Instant now = databaseTime.now();
        Event event = Event.create(request.name(), request.venue(), request.startsAt(),
                request.saleStartsAt(), request.saleEndsAt(), request.priceMinor(),
                request.currency(), now);
        eventRepository.saveAndFlush(event);
        inventoryCatalogService.create(event.getId(), request.totalTickets(), now);
        return event;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Event update(UUID eventId, UpdateEventRequest request) {
        if (!request.isNotEmpty()) {
            throw new InvalidCatalogRequestException("at least one field must be supplied");
        }

        Instant now = databaseTime.now();
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        event.update(request.name(), request.venue(), request.startsAt(),
                request.saleStartsAt(), request.saleEndsAt(), request.status(),
                request.priceMinor(), request.currency(), now);

        if (request.addTickets() != null) {
            inventoryCatalogService.addTickets(eventId, request.addTickets(), now);
        }
        return event;
    }
}
