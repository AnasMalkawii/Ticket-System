-- Persist publisher retry state so a replica restart neither loses an event nor resets a
-- failing row into a tight retry loop. Backoff remains bounded; unpublished events are never
-- discarded because a broker outage can last longer than any arbitrary attempt limit.

ALTER TABLE outbox_event
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN last_error      VARCHAR(1000);

DROP INDEX ix_outbox_unpublished;

CREATE INDEX ix_outbox_ready
    ON outbox_event (next_attempt_at, created_at, id)
    WHERE published_at IS NULL;

COMMENT ON COLUMN outbox_event.next_attempt_at IS
    'Earliest database timestamp at which the publisher may retry this event.';
COMMENT ON COLUMN outbox_event.last_error IS
    'Truncated diagnostic from the latest failed publish attempt; null after broker ack.';
