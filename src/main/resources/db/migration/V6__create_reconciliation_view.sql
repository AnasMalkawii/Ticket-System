-- Reconciliation view backing the C1 correctness objective in docs/slo.md.
--
-- The CHECK constraints on ticket_inventory prove the counters are internally consistent.
-- They cannot prove the counters agree with the reservation ledger - counters could be
-- self-consistent and still wrong. This view cross-checks the two independent records of
-- the same truth, which is the check that actually catches a logic bug.
--
-- Run after every load scenario. Any row with a non-zero drift is a release blocker.

CREATE VIEW v_inventory_reconciliation AS
SELECT
    i.event_id,
    e.name                                          AS event_name,
    i.total,
    i.available,
    i.held,
    i.sold,
    -- I2: must be 0.
    (i.available + i.held + i.sold) - i.total       AS conservation_drift,
    COALESCE(r.pending_qty, 0)                      AS ledger_pending_qty,
    COALESCE(r.confirmed_qty, 0)                    AS ledger_confirmed_qty,
    -- Counters vs the reservation ledger: both must be 0.
    i.held - COALESCE(r.pending_qty, 0)             AS held_drift,
    i.sold - COALESCE(r.confirmed_qty, 0)           AS sold_drift
FROM ticket_inventory i
JOIN event e ON e.id = i.event_id
LEFT JOIN (
    SELECT
        event_id,
        COALESCE(SUM(qty) FILTER (WHERE status = 'PENDING'),   0) AS pending_qty,
        COALESCE(SUM(qty) FILTER (WHERE status = 'CONFIRMED'), 0) AS confirmed_qty
    FROM reservation
    GROUP BY event_id
) r ON r.event_id = i.event_id;

COMMENT ON VIEW v_inventory_reconciliation IS
    'Zero rows from: SELECT * FROM v_inventory_reconciliation WHERE conservation_drift <> 0 '
    'OR held_drift <> 0 OR sold_drift <> 0; - required after every concurrency test.';
