-- Reservation: the hold lifecycle and the retry identity.
--
-- Illegal states are made hard to represent: a PENDING hold must have a deadline and
-- no termination timestamp; a terminal hold must have a termination timestamp. The
-- database rejects a half-applied transition even if the service layer attempts one.

CREATE TABLE reservation (
    id                  UUID         NOT NULL,
    event_id            UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    qty                 INTEGER      NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    idempotency_key     VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    terminated_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_reservation PRIMARY KEY (id),
    CONSTRAINT fk_reservation_event
        FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE RESTRICT,

    -- I3: one idempotency key produces one logical reservation. Enforced here, in the
    -- database, and not in application memory: an in-JVM cache would break the moment a
    -- second replica exists. A duplicate INSERT blocks on this index until the first
    -- transaction resolves, which is what makes retries deterministic (F-01).
    CONSTRAINT uq_reservation_idempotency_key UNIQUE (idempotency_key),

    CONSTRAINT ck_reservation_qty_positive CHECK (qty > 0),
    CONSTRAINT ck_reservation_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_reservation_fingerprint_hex
        CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    -- A hold is either live with a deadline, or terminal with a termination time.
    -- There is no third shape.
    CONSTRAINT ck_reservation_terminal_consistency
        CHECK (
            (status =  'PENDING' AND terminated_at IS NULL)
         OR (status <> 'PENDING' AND terminated_at IS NOT NULL)
        )
);

-- Expiry worker: SELECT ... WHERE status = 'PENDING' AND expires_at <= now()
--                FOR UPDATE SKIP LOCKED
-- A partial index keeps this tiny: terminal rows accumulate forever but never enter it,
-- so the worker's scan cost is proportional to live holds, not to table size.
CREATE INDEX ix_reservation_pending_expiry
    ON reservation (expires_at)
    WHERE status = 'PENDING';

-- Per-user cap check (I6), evaluated inside the reserve transaction after the lock.
--
-- Deliberately NOT a partial index. A partial index predicated on
-- `status IN ('PENDING','CONFIRMED')` only matches when the planner can prove the query
-- predicate implies it, which it cannot do once the status list arrives as a bind
-- parameter (`status = ANY($1)`) - exactly how JPA sends it. A plain composite index
-- always matches.
--
-- INCLUDE (qty) makes the cap check an index-only scan: it runs inside the inventory row
-- lock, so every heap fetch avoided there is time subtracted from the critical section
-- for every other user waiting on the same event.
CREATE INDEX ix_reservation_user_event_status
    ON reservation (user_id, event_id, status)
    INCLUDE (qty);

-- Reconciliation and per-event reporting.
CREATE INDEX ix_reservation_event_status ON reservation (event_id, status);

COMMENT ON COLUMN reservation.request_fingerprint IS
    'SHA-256 of the canonical request body. Lets a replay be distinguished from the same '
    'Idempotency-Key reused with a different payload (409 IDEMPOTENCY_KEY_CONFLICT).';
COMMENT ON COLUMN reservation.expires_at IS
    'Hold deadline, assigned at creation and retained after termination for audit.';
