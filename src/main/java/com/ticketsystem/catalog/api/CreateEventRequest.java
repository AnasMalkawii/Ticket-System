package com.ticketsystem.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Validated admin request for creating an event and its initial inventory row. */
public record CreateEventRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) String venue,
        Instant startsAt,
        @NotNull Instant saleStartsAt,
        Instant saleEndsAt,
        @NotNull @Positive Integer totalTickets,
        @NotNull @PositiveOrZero Long priceMinor,
        @NotBlank @Pattern(regexp = "[A-Z]{3}",
                message = "must be exactly three uppercase letters") String currency) {
}
