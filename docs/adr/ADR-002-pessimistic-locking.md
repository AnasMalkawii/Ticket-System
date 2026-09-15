# ADR-002: Pessimistic row locking on the reserve path

| | |
|---|---|
| **Status** | Superseded on Day 9 by the measured conditional atomic update; comparison branch retained |
| **Date** | 2026-08-22 (Week 1, Day 1) |
| **Deciders** | Backend |
| **Related** | [ADR-001](ADR-001-postgresql-inventory-authority.md) · [architecture.md](../architecture.md) · [slo.md](../slo.md) · [failure-matrix.md](../failure-matrix.md) |

---

## Day 9 decision update

The scheduled two-replica comparison was completed at 500, 1,000, and 2,000 VUs. The
conditional atomic update preserved every inventory invariant while improving p95 at all
three levels (18.6%, 25.4%, and 14.3%) and increasing completed-iteration throughput (19.2%,
32.8%, and 34.1%). It is therefore the production default. The explicit pessimistic branch
remains available for regression benchmarks. Full method, environment, raw counts, and
reconciliation are recorded in [the Day 9 report](../day9-scaling-benchmark.md).

---

## Context

[ADR-001](ADR-001-postgresql-inventory-authority.md) establishes that PostgreSQL owns
inventory. This ADR decides **how concurrent transactions are serialised** when hundreds of
requests reserve from the same `ticket_inventory` row simultaneously.

The naive implementation is the classic bug:

```sql
SELECT available FROM ticket_inventory WHERE event_id = ?;   -- reads 1
-- another transaction reads 1 here, before either writes
UPDATE ticket_inventory SET available = available - 1 WHERE event_id = ?;
```

Both transactions read `available = 1`, both decide the sale is legal, both decrement.
`available` becomes `-1` and two people own the last ticket. Under PostgreSQL's default
`READ COMMITTED` isolation this is not a rare interleaving — it is the *expected* outcome at
500 concurrent virtual users, because the read is not protected against a write that lands
between the check and the update.

The decision must also survive N application replicas, so any mechanism that lives inside a
single JVM is disqualified before evaluation begins.

### Decision drivers

1. **Correct before fast.** Make the race impossible, then measure and optimise.
2. **Simple to reason about and to review.** A reviewer should be able to see the invariant
   is safe without simulating thread schedules.
3. **Must give good error messages.** Users need `SOLD_OUT` vs `USER_LIMIT_EXCEEDED` vs
   `SALE_NOT_STARTED`, not a generic conflict.
4. **Must work identically across replicas.**
5. **Must be measurable**, so that any later optimisation can be justified by a
   before/after number rather than by taste.

---

## Considered options

### Option A — Pessimistic row lock, `SELECT ... FOR UPDATE` *(chosen)*

Acquire an exclusive row lock on the event's inventory row, then perform every validation
and the write inside that lock, in one transaction.

- **For:** The race is eliminated by construction — concurrent reservers for one event
  execute strictly one at a time. Every business rule (sale window, per-user cap,
  availability) is evaluated against committed, locked state, so all of them can return a
  precise domain error. No retry loop, so latency is predictable and there is no
  livelock/thundering-herd behaviour. The mental model — "hold the row, decide, write,
  commit" — is short enough to verify by reading.
- **Against:** Serialises all reservers for one event; throughput is bounded by
  `1 / (lock hold time)`. Waiting requests occupy a connection each. A deadlock is possible
  if lock ordering is inconsistent across code paths.

### Option B — Optimistic locking (JPA `@Version`) with retry

Read the row, compute, update with a version check, retry on conflict.

- **For:** No blocking; excellent when contention is rare.
- **Against:** This row is contended **by design** — a deliberately hot single row is the
  worst possible case for optimistic control. At 500 VUs nearly every transaction loses its
  version check and retries, converting one cheap lock wait into repeated full transaction
  attempts: more CPU, more connection churn, worse and far less predictable tail latency.
  Retry storms are also the mechanism by which a spike becomes an outage.
- **Verdict:** Rejected for the hot path. Optimistic control is the right tool for
  low-contention rows (catalog edits), not for a flash-sale counter.

### Option C — Conditional atomic update

```sql
UPDATE ticket_inventory
   SET available = available - :qty, held = held + :qty
 WHERE event_id = :id AND available >= :qty;
-- 0 rows affected => sold out
```

- **For:** Single statement, no explicit lock section, very likely the fastest option; the
  row lock is still taken internally by the `UPDATE`, so it is genuinely safe against
  overselling.
- **Against:** The other business rules — per-user cap (**I6**), sale window — still need
  their own consistent evaluation, so the transaction does not collapse to one statement.
  Distinguishing "sold out" from "user cap exceeded" from "sale closed" requires extra
  queries whose results must be consistent with the update, which pushes the complexity back
  into ordering. A bare `0 rows affected` is a poor error contract.
- **Verdict:** Not rejected — **deferred**. This is the designated optimisation branch,
  benchmarked head-to-head on Day 9 (see *Revisit triggers*). It is deferred rather than
  adopted because Day 3's job is to make the race correct, not to pick a winner before the
  contention profile has been measured.

### Option D — `SERIALIZABLE` isolation

- **For:** Strongest correctness guarantee; the database detects the anomaly for us.
- **Against:** Serialisation failures must be retried by the application, so it inherits
  Option B's retry-storm behaviour under high contention plus a heavier predicate-locking
  cost. It solves a problem — arbitrary anomalies across many rows — that we do not have,
  since the contention is one known row.
- **Verdict:** Rejected. More machinery for less predictability on this workload.

### Option E — Distributed lock in Redis (Redlock or similar)

- **For:** Removes the lock from the database.
- **Against:** Introduces a *second* coordination system whose correctness depends on
  clocks, TTLs, and failover semantics, guarding data that lives in a system that already
  has a correct lock. A lease expiring early during a GC pause silently permits two holders,
  which is exactly the oversell we are eliminating. It also violates
  [ADR-001](ADR-001-postgresql-inventory-authority.md).
- **Verdict:** Rejected. Adding a weaker lock to protect a stronger store is a net loss.

### Option F — Serialise through a queue (single consumer per event)

- **For:** Eliminates contention; a natural fit for extreme flash sales.
- **Against:** Turns a synchronous API into an asynchronous one, requiring client polling or
  push. Makes the consumer a single point of failure with its own partition and ordering
  concerns. A large redesign for a bottleneck that has not been measured yet.
- **Verdict:** Rejected for v1.0; a virtual waiting room is the post-v1.0 direction if a
  spike ever demands it.

---

## Decision

**The initial reserve path uses a pessimistic exclusive row lock
(`SELECT ... FOR UPDATE`, JPA `PESSIMISTIC_WRITE`) on the event's `ticket_inventory` row.
All validation and all writes happen inside that lock, in a single transaction.**

```sql
BEGIN;

-- 1. Idempotency first: cheapest possible rejection of a duplicate,
--    before any contended resource is touched.
INSERT INTO reservation (id, event_id, user_id, qty, status, expires_at, idempotency_key)
VALUES (...);                              -- unique violation => rollback and replay

-- 2. Acquire the lock. Every concurrent reserver for this event queues here.
SELECT total, available, held, sold
  FROM ticket_inventory
 WHERE event_id = :eventId
   FOR UPDATE;

-- 3. Validate against committed, locked state — never against an earlier read.
--    sale window open, qty within 1..4, per-user cap not exceeded, available >= qty

-- 4. Move the counters.
UPDATE ticket_inventory
   SET available = available - :qty,
       held      = held + :qty,
       updated_at = now()
 WHERE event_id = :eventId;

-- 5. Record the event for asynchronous consumers, same transaction.
INSERT INTO outbox_event (...) VALUES (...);

COMMIT;
```

### Implementation rules

These are the rules that make the choice safe in practice. They are review-blocking.

1. **Every check that guards an invariant happens after the lock is acquired**, in the same
   transaction. A value read before the lock — from Redis, from an earlier query, or from a
   previous request — is never used to authorise a decrement.
2. **The critical section contains no slow work.** No HTTP calls, no payment, no broker
   publish, no email, no logging that can block. Lock hold time is the throughput ceiling
   (see [architecture.md](../architecture.md#13-scaling-strategy-and-known-limits)), so
   anything avoidable inside it is a direct throughput tax on every other user.
3. **Consistent lock ordering: `reservation` before `ticket_inventory`, always.** Confirm,
   cancel, and the expiry worker all lock the reservation row first and the inventory row
   second. Uniform ordering is what makes deadlock structurally impossible; a batch touching
   several events additionally orders by `event_id` ascending.
4. **`lock_timeout = 2s`, `statement_timeout = 3s`.** A waiter fails fast with a retryable
   error rather than occupying a connection indefinitely
   ([F-14](../failure-matrix.md)).
5. **Idempotency is checked before the lock**, so duplicate retries never join the queue for
   a contended row.
6. **`FOR UPDATE`, not `FOR NO KEY UPDATE`.** `ticket_inventory` is not the target of any
   foreign key, so the stronger lock costs nothing here; the weaker mode would only matter
   for a row other tables reference.
7. **`FOR UPDATE SKIP LOCKED`** is used *only* in the expiry worker, where skipping a row
   another worker already holds is exactly the desired behaviour
   ([F-05](../failure-matrix.md)). It is never used on the reserve path, where skipping
   would silently mean "pretend the inventory row does not exist".
8. **Database constraints remain the backstop.** `CHECK (available >= 0)` and
   `CHECK (available + held + sold = total)` mean a logic bug produces a failed transaction,
   not an oversell.

---

## Consequences

### Positive

- Overselling is impossible on this path by construction, not by probability.
- Latency is predictable: a request waits once, then proceeds. There is no retry loop, so
  there is no retry storm and no unbounded tail.
- Precise domain errors are natural, because all rules are evaluated together against locked
  state.
- Identical semantics across replicas, because the lock lives in the database
  ([F-11](../failure-matrix.md)).
- Lock wait is directly observable (`inventory_lock_wait_seconds`, [L6](../slo.md)), which
  makes the bottleneck a measured fact rather than a hypothesis.

### Negative — accepted, with mitigations

- **Serialised throughput per event.** *Mitigation:* minimal critical section; measured on
  Day 6; documented as a known limit rather than hidden.
- **Waiting requests hold a connection.** *Mitigation:* bounded pool plus `lock_timeout`, so
  saturation degrades into fast failures instead of a hang ([F-15](../failure-matrix.md)).
- **Lock wait inflates p95 as concurrency rises.** *Mitigation:* it is measured separately
  from total latency, so a missed [L1](../slo.md) can be attributed rather than guessed at.
- **Deadlock risk if ordering rules are broken later.** *Mitigation:* rule 3 above, plus
  deadlock detection surfacing as a logged, alertable error rather than a silent stall.

---

## Revisit triggers — the Day 9 benchmark

This decision is deliberately scheduled for re-evaluation. On Day 9, **if** lock wait is
shown to dominate reserve latency at 500-2,000 VUs, Option C (conditional atomic update) is
implemented as an alternative branch and benchmarked head-to-head on the same fixture.

The winner is kept only if it satisfies **all** of the following:

1. Measurably better p95/p99 at equal or higher throughput, on the same environment.
2. **Zero** oversells across the full concurrency suite — invariants I1-I6 intact.
3. The error contract is preserved: `SOLD_OUT`, `USER_LIMIT_EXCEEDED`, `SALE_NOT_STARTED`
   remain distinguishable.
4. Before/after numbers are recorded in `results.md` with the environment stated.

If any condition fails, the pessimistic path stays. **The decision is settled by
measurement, not by preference** — and either outcome is a good outcome, because a rejected
optimisation backed by numbers is stronger evidence of engineering judgement than an adopted
one backed by intuition.
