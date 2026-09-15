package com.ticketsystem.catalog.api;

import com.ticketsystem.catalog.application.CatalogQueryService;
import com.ticketsystem.catalog.domain.EventStatus;
import com.ticketsystem.catalog.domain.InvalidCatalogRequestException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, eventually-consistent catalog endpoints. */
@RestController
@RequestMapping("/api/v1/events")
public class CatalogController {

    private final CatalogQueryService catalogQueryService;

    public CatalogController(CatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    @GetMapping
    public EventPageResponse listEvents(
            @RequestParam(required = false) EventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new InvalidCatalogRequestException("page must be non-negative");
        }
        if (size < 1 || size > 100) {
            throw new InvalidCatalogRequestException("size must be between 1 and 100");
        }
        return catalogQueryService.listEvents(status, page, size);
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable UUID eventId) {
        return catalogQueryService.getEvent(eventId);
    }

    @GetMapping("/{eventId}/availability")
    public AvailabilityResponse getAvailability(@PathVariable UUID eventId) {
        return catalogQueryService.getAvailability(eventId);
    }
}
