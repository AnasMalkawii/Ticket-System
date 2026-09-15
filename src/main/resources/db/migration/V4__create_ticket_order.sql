-- Confirmation / payment boundary.
--
-- Named ticket_order rather than "order": ORDER is a reserved word in the SQL standard
-- and in PostgreSQL, so an unquoted `order` table is invalid and a quoted "order" forces
-- quoting at every use site, in every tool, forever. The rename is cheaper than the
-- lifetime cost of the quoting.

CREATE TABLE ticket_order (
    id                UUID         NOT NULL,
    reservation_id    UUID         NOT NULL,
    user_id           UUID         NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    amount_minor      BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    payment_reference VARCHAR(128),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_ticket_order PRIMARY KEY (id),
    CONSTRAINT fk_ticket_order_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservation (id) ON DELETE RESTRICT,

    -- I5: confirmation consumes the same hold exactly once. This single constraint is
    -- what makes a retried or redelivered confirmation safe (F-07).
    CONSTRAINT uq_ticket_order_reservation UNIQUE (reservation_id),

    CONSTRAINT ck_ticket_order_status CHECK (status IN ('PAID', 'FAILED')),
    CONSTRAINT ck_ticket_order_amount_non_negative CHECK (amount_minor >= 0),
    CONSTRAINT ck_ticket_order_currency_iso CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ix_ticket_order_user_created ON ticket_order (user_id, created_at DESC);
