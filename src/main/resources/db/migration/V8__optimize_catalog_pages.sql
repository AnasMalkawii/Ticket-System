-- Day 9 catalog pages return a projection ordered by sale start and UUID. These covering
-- indexes avoid loading audit columns and avoid a separate sort for both public list shapes.

CREATE INDEX ix_event_catalog_page
    ON event (sale_starts_at DESC, id DESC)
    INCLUDE (name, venue, starts_at, sale_ends_at, status, price_minor, currency);

CREATE INDEX ix_event_status_catalog_page
    ON event (status, sale_starts_at DESC, id DESC)
    INCLUDE (name, venue, starts_at, sale_ends_at, price_minor, currency);

-- Superseded by the status index above, whose id tie-breaker exactly matches the API order.
DROP INDEX ix_event_status_sale_starts_at;
