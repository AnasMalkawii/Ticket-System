package com.ticketsystem.reservation.application;

/** Selects the measured implementation of the hot inventory reservation step. */
public enum InventoryReservationStrategy {
    /** Explicit SELECT FOR UPDATE followed by a managed-entity update. */
    PESSIMISTIC,

    /** One conditional UPDATE that acquires the same PostgreSQL row lock internally. */
    ATOMIC
}
