package com.ticketsystem.order.api;

import jakarta.validation.constraints.Size;

/** Optional deterministic mock-payment instruction from the public API contract. */
public record ConfirmReservationRequest(
        @Size(max = 128, message = "paymentToken must contain at most 128 characters")
        String paymentToken) {

    public String tokenOrDefault() {
        return paymentToken == null || paymentToken.isBlank() ? "tok_ok" : paymentToken;
    }
}
