-- The authoritative inventory row. See docs/adr/ADR-001-postgresql-inventory-authority.md.
--
-- Exactly one row per event: deliberately the hottest possible row, because proving
-- correctness under maximum contention is the point of the project.
--
-- The CHECK constraints below are the mechanical backstop for invariants I1 and I2.
-- Even if the service layer has a bug, a violating transaction fails to COMMIT rather
-- than committing bad data. Overselling requires defeating both the row lock and these.

CREATE TABLE ticket_inventory (
    event_id   UUID        NOT NULL,
    total      INTEGER     NOT NULL,
    available  INTEGER     NOT NULL,
    held       INTEGER     NOT NULL,
    sold       INTEGER     NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_ticket_inventory PRIMARY KEY (event_id),
    CONSTRAINT fk_ticket_inventory_event
        FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE RESTRICT,

    CONSTRAINT ck_inventory_total_positive   CHECK (total     >  0),
    -- I1: no overselling, under any interleaving.
    CONSTRAINT ck_inventory_available_non_negative CHECK (available >= 0),
    CONSTRAINT ck_inventory_held_non_negative      CHECK (held      >= 0),
    CONSTRAINT ck_inventory_sold_non_negative      CHECK (sold      >= 0),
    -- I2: inventory conservation. Counters may move between buckets, never leak.
    CONSTRAINT ck_inventory_conservation
        CHECK (available + held + sold = total)
);

COMMENT ON TABLE  ticket_inventory IS
    'Authoritative ticket counters. Locked with SELECT ... FOR UPDATE on the reserve path.';
COMMENT ON COLUMN ticket_inventory.version IS
    'Observability counter of row mutations. NOT a JPA @Version - v1.0 does not use '
    'optimistic locking for correctness (see ADR-002).';
