-- Catalog: events and their sale windows.
-- Events are never hard-deleted; a withdrawn event becomes status = 'CANCELLED'.
-- Foreign keys therefore use ON DELETE RESTRICT throughout.

CREATE TABLE event (
    id             UUID         NOT NULL,
    name           VARCHAR(200) NOT NULL,
    venue          VARCHAR(200) NOT NULL,
    starts_at      TIMESTAMPTZ,
    sale_starts_at TIMESTAMPTZ  NOT NULL,
    sale_ends_at   TIMESTAMPTZ,
    status         VARCHAR(20)  NOT NULL,
    price_minor    BIGINT       NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_event PRIMARY KEY (id),

    -- Status is constrained in the database as well as in the Java enum, so a bad
    -- write from any client (psql, a future service, a migration) is rejected too.
    CONSTRAINT ck_event_status
        CHECK (status IN ('SCHEDULED', 'ON_SALE', 'SOLD_OUT', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_event_price_non_negative
        CHECK (price_minor >= 0),
    CONSTRAINT ck_event_currency_iso
        CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_event_sale_window_ordered
        CHECK (sale_ends_at IS NULL OR sale_ends_at > sale_starts_at)
);

-- Catalog listing filters by status and orders by sale start.
CREATE INDEX ix_event_status_sale_starts_at ON event (status, sale_starts_at DESC);

COMMENT ON TABLE  event IS 'Catalog of events. Never the authority for ticket counts.';
COMMENT ON COLUMN event.price_minor IS 'Unit price in minor currency units (e.g. cents).';
