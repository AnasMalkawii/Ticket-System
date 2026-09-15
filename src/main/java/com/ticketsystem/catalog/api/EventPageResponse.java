package com.ticketsystem.catalog.api;

import com.ticketsystem.catalog.repository.EventCatalogView;
import java.util.List;
import org.springframework.data.domain.Page;

/** Stable cache/API representation instead of serializing Spring Data's {@code PageImpl}. */
public record EventPageResponse(List<EventResponse> content,
                                int page,
                                int size,
                                long totalElements,
                                int totalPages) {

    public EventPageResponse {
        content = List.copyOf(content);
    }

    public static EventPageResponse from(Page<EventCatalogView> events) {
        return new EventPageResponse(events.getContent().stream()
                .map(EventResponse::from)
                .toList(), events.getNumber(), events.getSize(),
                events.getTotalElements(), events.getTotalPages());
    }
}
