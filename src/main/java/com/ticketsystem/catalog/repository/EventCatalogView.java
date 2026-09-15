package com.ticketsystem.catalog.repository;

import com.ticketsystem.catalog.domain.EventStatus;
import java.time.Instant;
import java.util.UUID;

/** Closed projection for catalog pages so internal audit columns are not materialized. */
public interface EventCatalogView {

    UUID getId();

    String getName();

    String getVenue();

    Instant getStartsAt();

    Instant getSaleStartsAt();

    Instant getSaleEndsAt();

    EventStatus getStatus();

    long getPriceMinor();

    String getCurrency();
}
