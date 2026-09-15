package com.ticketsystem.catalog.repository;

/** Event data and the transaction's PostgreSQL clock value from one database round trip. */
public interface EventWithDatabaseTime {

    Long getSaleStartsAtEpochMillis();

    Long getSaleEndsAtEpochMillis();

    String getStatus();

    Long getDatabaseEpochMillis();
}
