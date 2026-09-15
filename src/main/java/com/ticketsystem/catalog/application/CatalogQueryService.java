package com.ticketsystem.catalog.application;

import com.ticketsystem.catalog.api.AvailabilityResponse;
import com.ticketsystem.catalog.api.EventPageResponse;
import com.ticketsystem.catalog.api.EventResponse;
import com.ticketsystem.catalog.cache.CatalogCaches;
import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.catalog.domain.EventStatus;
import com.ticketsystem.catalog.repository.EventCatalogView;
import com.ticketsystem.catalog.repository.EventRepository;
import com.ticketsystem.inventory.application.InventoryCatalogService;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cache-aside catalog reads backed by PostgreSQL on every miss or Redis failure. */
@Service
public class CatalogQueryService {

    private static final Sort CATALOG_ORDER = Sort.by(
            Sort.Order.desc("saleStartsAt"), Sort.Order.desc("id"));

    private final EventRepository eventRepository;
    private final InventoryCatalogService inventoryCatalogService;

    public CatalogQueryService(EventRepository eventRepository,
                               InventoryCatalogService inventoryCatalogService) {
        this.eventRepository = eventRepository;
        this.inventoryCatalogService = inventoryCatalogService;
    }

    @Cacheable(cacheNames = CatalogCaches.EVENT_PAGES,
            key = "(#status == null ? 'ALL' : #status.name()) + ':' + #page + ':' + #size",
            cacheManager = "catalogCacheManager")
    @Transactional(readOnly = true)
    public EventPageResponse listEvents(EventStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, CATALOG_ORDER);
        Page<EventCatalogView> events = status == null
                ? eventRepository.findAllProjectedBy(pageable)
                : eventRepository.findByStatus(status, pageable);
        return EventPageResponse.from(events);
    }

    @Cacheable(cacheNames = CatalogCaches.EVENT_DETAILS, key = "#eventId",
            cacheManager = "catalogCacheManager")
    @Transactional(readOnly = true)
    public EventResponse getEvent(UUID eventId) {
        return eventRepository.findById(eventId)
                .map(EventResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @Cacheable(cacheNames = CatalogCaches.AVAILABILITY, key = "#eventId",
            cacheManager = "catalogCacheManager")
    public AvailabilityResponse getAvailability(UUID eventId) {
        return inventoryCatalogService.findAdvisorySnapshot(eventId)
                .map(AvailabilityResponse::from)
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }
}
