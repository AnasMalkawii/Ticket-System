package com.ticketsystem.catalog.api;

import com.ticketsystem.catalog.domain.Event;
import com.ticketsystem.catalog.domain.EventStatus;
import com.ticketsystem.catalog.repository.EventCatalogView;
import java.time.Instant;
import java.util.UUID;

/** Public event representation defined by the OpenAPI {@code Event} schema. */
public record EventResponse(UUID id,
                            String name,
                            String venue,
                            Instant startsAt,
                            Instant saleStartsAt,
                            Instant saleEndsAt,
                            EventStatus status,
                            long priceMinor,
                            String currency) {

    public static EventResponse from(Event event) {
        return new EventResponse(event.getId(), event.getName(), event.getVenue(),
                event.getStartsAt(), event.getSaleStartsAt(), event.getSaleEndsAt(),
                event.getStatus(), event.getPriceMinor(), event.getCurrency());
    }

    public static EventResponse from(EventCatalogView event) {
        return new EventResponse(event.getId(), event.getName(), event.getVenue(),
                event.getStartsAt(), event.getSaleStartsAt(), event.getSaleEndsAt(),
                event.getStatus(), event.getPriceMinor(), event.getCurrency());
    }
}
