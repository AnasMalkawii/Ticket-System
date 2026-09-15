package com.ticketsystem.catalog.domain;

public enum EventStatus {
    SCHEDULED,
    ON_SALE,
    SOLD_OUT,
    CLOSED,
    CANCELLED;

    /** Whether tickets may be reserved at all, before the sale window is consulted. */
    public boolean allowsReservation() {
        return this == ON_SALE;
    }
}
