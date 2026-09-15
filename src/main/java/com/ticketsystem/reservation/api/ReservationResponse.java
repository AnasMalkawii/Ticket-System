package com.ticketsystem.reservation.api;

import com.ticketsystem.reservation.domain.Reservation;
import java.time.Instant;
import java.util.UUID;

/** Reservation representation, matching the {@code Reservation} schema in the OpenAPI contract. */
public record ReservationResponse(UUID id,
                                  UUID eventId,
                                  UUID userId,
                                  int quantity,
                                  String status,
                                  Instant expiresAt,
                                  Instant terminatedAt,
                                  Instant createdAt) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getEventId(),
                reservation.getUserId(),
                reservation.getQty(),
                reservation.getStatus().name(),
                reservation.getExpiresAt(),
                reservation.getTerminatedAt(),
                reservation.getCreatedAt());
    }
}
