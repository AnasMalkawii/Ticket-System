package com.ticketsystem.reservation.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Reserve request body.
 *
 * <p>Only shape is validated here. The upper bound lives in configuration and is enforced by
 * the service, so {@code ticketing.reservation.max-quantity-per-request} stays the single
 * source of truth rather than being duplicated in an annotation.
 */
public record CreateReservationRequest(
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be positive")
        Integer quantity) {
}
