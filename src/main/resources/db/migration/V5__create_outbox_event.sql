-- Transactional outbox: reliable event publication without a dual write.
--
-- The event row is written by the SAME transaction that changes reservation/order state,
-- so a crash can never leave the database and the broker disagreeing. Publishing is a
-- separate, retryable step that marks a row published only after broker acknowledgement.

CREATE TABLE outbox_event (
    id             UUID         NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    type           VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    correlation_id VARCHAR(128),
    attempts       INTEGER      NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,

    CONSTRAINT pk_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_outbox_attempts_non_negative CHECK (attempts >= 0)
);

-- The publisher only ever scans unpublished rows. A partial index means the backlog
-- query stays fast no matter how many millions of published rows have accumulated,
-- and it directly serves the F4 "oldest unpublished row age" metric.
CREATE INDEX ix_outbox_unpublished
    ON outbox_event (created_at)
    WHERE published_at IS NULL;

CREATE INDEX ix_outbox_aggregate ON outbox_event (aggregate_type, aggregate_id);

-- Consumer-side dedupe. Delivery is at-least-once; consumption is idempotent, which is
-- observationally exactly-once (F-21).
CREATE TABLE processed_event (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_event PRIMARY KEY (event_id, consumer)
);

COMMENT ON TABLE processed_event IS
    'Idempotency ledger for consumers. Composite PK means a redelivered message fails to '
    'insert and the handler becomes a no-op.';
