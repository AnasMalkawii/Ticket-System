package com.ticketsystem.catalog.application;

import com.ticketsystem.catalog.api.CreateEventRequest;
import com.ticketsystem.catalog.api.EventResponse;
import com.ticketsystem.catalog.api.UpdateEventRequest;
import com.ticketsystem.catalog.cache.CatalogCacheInvalidator;
import com.ticketsystem.catalog.domain.Event;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Coordinates committed admin writes with best-effort Redis invalidation. */
@Service
public class AdminCatalogService {

    private final AdminCatalogTransaction transaction;
    private final CatalogCacheInvalidator cacheInvalidator;

    public AdminCatalogService(AdminCatalogTransaction transaction,
                               CatalogCacheInvalidator cacheInvalidator) {
        this.transaction = transaction;
        this.cacheInvalidator = cacheInvalidator;
    }

    public EventResponse create(CreateEventRequest request) {
        Event committed = transaction.create(request);
        cacheInvalidator.afterCreate();
        return EventResponse.from(committed);
    }

    public EventResponse update(UUID eventId, UpdateEventRequest request) {
        Event committed = transaction.update(eventId, request);
        cacheInvalidator.afterUpdate(eventId, request.addTickets() != null);
        return EventResponse.from(committed);
    }
}
