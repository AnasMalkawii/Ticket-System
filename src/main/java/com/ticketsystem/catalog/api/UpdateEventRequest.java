package com.ticketsystem.catalog.api;

import com.ticketsystem.catalog.domain.EventStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Validated partial admin update. Null means that the field was not supplied. */
public record UpdateEventRequest(
        @Size(min = 1, max = 200)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(min = 1, max = 200)
        @Pattern(regexp = ".*\\S.*", message = "must not be blank") String venue,
        Instant startsAt,
        Instant saleStartsAt,
        Instant saleEndsAt,
        EventStatus status,
        @PositiveOrZero Long priceMinor,
        @Pattern(regexp = "[A-Z]{3}",
                message = "must be exactly three uppercase letters") String currency,
        @Positive Integer addTickets) {

    @AssertTrue(message = "at least one field must be supplied")
    public boolean isNotEmpty() {
        return name != null || venue != null || startsAt != null || saleStartsAt != null
                || saleEndsAt != null || status != null || priceMinor != null
                || currency != null || addTickets != null;
    }
}
