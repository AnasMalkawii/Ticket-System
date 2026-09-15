package com.ticketsystem.catalog.cache;

/** Cache names are public so integration tests and operational tooling can inspect TTLs. */
public final class CatalogCaches {

    public static final String EVENT_DETAILS = "eventDetails";
    public static final String EVENT_PAGES = "eventPages";
    public static final String AVAILABILITY = "availability";

    private CatalogCaches() {
    }
}
