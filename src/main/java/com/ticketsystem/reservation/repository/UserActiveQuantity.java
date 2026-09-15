package com.ticketsystem.reservation.repository;

import java.util.UUID;

/** Closed projection for one user's active quantity in a batch cap check. */
public interface UserActiveQuantity {

    UUID getUserId();

    Long getQuantity();
}
