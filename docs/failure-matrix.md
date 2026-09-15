# Failure Matrix

| | |
|---|---|
| **Status** | Accepted — baseline for v1.0.0 |
| **Last updated** | 2026-08-22 (Week 1, Day 1) |
| **Related** | [architecture.md](architecture.md) · [slo.md](slo.md) · [ADR-001](adr/ADR-001-postgresql-inventory-authority.md) · [ADR-002](adr/ADR-002-pessimistic-locking.md) |

---

## 1. Purpose

This is the Day 1 acceptance gate: **every failure below has a named containment mechanism,
a stated expected behaviour, and a test that will prove it — decided before implementation
starts.**

The point is not to enumerate everything that can go wrong. It is to guarantee that no
failure mode is discovered for the first time during a load test, and that no failure mode
resolves into "we lose or duplicate tickets". Failures are allowed to make the system
slower, louder, or temporarily unavailable. They are not allowed to make it *wrong*.

Each row states:

- **Trigger** — how the failure is deliberately induced in a drill.
- **Containment** — the specific mechanism that bounds the damage.
- **Expected behaviour** — what a client and an operator actually observe.
- **Invariant** — which of **I1-I6** ([architecture.md](architecture.md#51-business-invariants))
  stays intact.
- **Verified by** — the test or drill that proves it, and the roadmap day it lands.

---

## 2. Severity classes

| Class | Meaning | Acceptable outcome |
|---|---|---|
| **S0** | Correctness violation | Never acceptable. Release blocker. |
| **S1** | Availability loss on the booking path | Acceptable briefly; must self-heal and alert |
| **S2** | Degraded performance or delayed async work | Acceptable within [slo.md](slo.md) targets |
| **S3** | Non-critical feature degradation | Acceptable; must be visible in metrics |

Every row below is designed so that the failure lands in **S1-S3**. Any row that could
produce an S0 outcome is, by definition, a design defect and is fixed at design time — which
is what this document is for.

---

## 3. The matrix

Rows are grouped by theme, so **F-IDs are stable identifiers rather than a reading
order** — they are referenced from [architecture.md](architecture.md), the ADRs, and
drill notes, and never renumbered once assigned.

### 3.1 Concurrency and client behaviour

| ID | Failure | Trigger | Containment | Expected behaviour | Invariant | Class | Verified by |
|---|---|---|---|---|---|---|---|
| **F-01** | Duplicate POST, same `Idempotency-Key` | Send the same reserve request 10-50x in parallel | `UNIQUE` index on `reservation.idempotency_key`; duplicate insert blocks then fails; original result replayed | Exactly 1 hold. First call `201`; duplicates `201` + `Idempotency-Replayed: true`. Under a slow winner, `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After` | I3 | S3 | Concurrency test (Day 4), k6 duplicate scenario (Day 6) |
| **F-02** | Client times out *after* the commit | Kill the client connection between `COMMIT` and response | The write is durable; the client resolves ambiguity by retrying with the same key | Retry returns the original reservation, not a second hold. No inventory leak | I3 | S3 | Integration test (Day 4), drill (Day 8) |
| **F-03** | Inventory race | 100 tickets, 500 VUs, simultaneous 1-ticket reserves | `SELECT ... FOR UPDATE` on the single inventory row; all checks after the lock, inside the transaction | Exactly 100 accepted; the rest `409 SOLD_OUT`. `available` never negative | I1, I2 | S0 if violated | Testcontainers barrier test (Day 3), k6 Gate 2 (Day 6) |
| **F-04** | Burst sold-out | Traffic continues after inventory hits zero | Post-lock availability check returns a fast domain rejection | `409 SOLD_OUT` quickly and cheaply. **No 5xx storm**; error budget untouched | I1 | S3 | k6 sold-out spike (Day 6) |
| **F-05** | Expiry race, two workers | Two replicas process the same expired batch simultaneously | `FOR UPDATE SKIP LOCKED` + `status = 'PENDING'` guard | Each hold released exactly once; the second worker skips locked rows and no-ops on already-transitioned ones | I4, I2 | S0 if violated | Two-worker concurrency test (Day 4/11) |
| **F-06** | Cancel races expiry | User cancels at the instant the TTL elapses | Row lock + single-transition guard | Exactly one transition wins (`CANCELLED` or `EXPIRED`); inventory returned once; loser gets `409 INVALID_STATE` | I4, I2 | S3 | Concurrency test (Day 4/11) |
| **F-07** | Confirm retried or duplicated | Same confirm delivered/retried N times | Status guard on `PENDING` + `UNIQUE(reservation_id)` on `ticket_order` | One order, one `sold` increment. Retries get the original result or `409 INVALID_STATE` | I5 | S0 if violated | `ReservationMutationConcurrencyIT.simultaneousConfirmationsHaveOneWinner` |
| **F-16** | Per-user limit race | One user fires parallel reserves for the same event | Cap counted **after** the inventory row lock is held, in the same transaction | Total active + confirmed qty never exceeds the cap; excess requests get `409 USER_LIMIT_EXCEEDED` | I6 | S0 if violated | Concurrency test (Day 3/11) |

### 3.2 Dependency failures

| ID | Failure | Trigger | Containment | Expected behaviour | Invariant | Class | Verified by |
|---|---|---|---|---|---|---|---|
| **F-08** | RabbitMQ outage during a state change | Stop the broker mid-confirmation, restart later | Transactional outbox: the event row commits with the state change; publishing happens after, only on broker ack | Booking commits normally. Events queue in `outbox_event` and drain after recovery — delivered at least once, consumed idempotently | I2, I5 | S2 | Broker-kill drill (Day 7/8) |
| **F-09** | Redis outage | Stop Redis under load | Redis is never on the correctness path. Catalog falls back to PostgreSQL; rate limiting fails open (documented, deliberate) | Bookings continue and stay correct. Catalog reads get slower; rate limiting is temporarily unenforced and alerts | I1, I2 | S2 | Redis-kill drill (Day 5/8) |
| **F-10** | Poison message | Consumer throws repeatedly on one message | Bounded exponential backoff, then route to DLQ | Message lands in the DLQ after N attempts; the queue keeps moving; DLQ depth > 0 alerts | — | S2 | Consumer test (Day 7) |
| **F-21** | Duplicate event delivery | Broker redelivers after a missed ack | `processed_event` dedupe keyed on `event_id` | Side effect applied exactly once; redelivery is a logged no-op | I5 | S3 | Consumer idempotency test (Day 7/11) |
| **F-13** | PostgreSQL restart | Restart the database during load | Connection pool detects failure; readiness probe fails; the replica is removed from the load balancer | In-flight transactions roll back — never half-applied. Clients get `503`/connection errors briefly, then recovery. **No partial inventory moves** | I1, I2 | S1 | `HealthProbeRecoveryIT.databaseRestartPreservesBookingAndDuplicateIdentity` |
| **F-17** | Payment gateway failure or timeout | Mock gateway returns error/hangs past its timeout | Explicit call timeout; payment happens **outside** the inventory lock; failed payment leaves the hold `PENDING` | `402 PAYMENT_DECLINED` or `504 DEPENDENCY_TIMEOUT`; the hold survives and expires naturally if unconfirmed. **Never blind-retried** — payment is not idempotent by itself | I5 | S3 | `ReserveApiIT` timeout drill (Day 8) |

### 3.3 Infrastructure and operations

| ID | Failure | Trigger | Containment | Expected behaviour | Invariant | Class | Verified by |
|---|---|---|---|---|---|---|---|
| **F-11** | Two replicas racing | Both replicas serve reserves for the same hot event | All coordination is in PostgreSQL; replicas share no state. The row lock is global to the database | Behaviour identical to a single instance — the reconciliation result is the same | I1, I2 | S0 if violated | Multi-instance k6 run (Day 9) |
| **F-12** | App replica crash mid-load | `docker kill` one replica at peak | Stateless replicas; load balancer health checks; uncommitted transactions roll back | The surviving replica serves traffic. Holds created by the dead replica remain valid and expire on TTL. Committed state stays correct | I1, I2 | S1 | Kill drill (Day 8) |
| **F-14** | Slow database / lock queue buildup | Introduce artificial latency or a long-running lock | `lock_timeout` 2 s, `statement_timeout` 3 s — bounded waits, never unbounded | Requests fail fast with `503`, not hang. Lock-wait metrics spike and alert; the system recovers when the source clears | I1 | S1 | Slow-DB drill (Day 8) |
| **F-15** | Connection pool exhaustion | Drive VUs past pool capacity | Bounded HikariCP pool with a connection-acquisition timeout; pool sized intentionally against `max_connections` | Requests queue briefly then fail fast with `503`. HikariCP `pending` gauge alerts. **The database is protected from connection storms** | I1 | S1 | k6 Gate 3 (Day 9) |
| **F-18** | Clock skew between replicas | Skew a container's clock | All time-sensitive comparisons (`expires_at`, sale windows) use **database time** (`now()`), not application time | TTL decisions stay consistent across replicas; skew cannot expire a hold early or keep one alive | I4 | S3 | Design rule + review (Day 4) |
| **F-19** | Bad deploy | Deploy an image that fails readiness or smoke tests | Immutable SHA-tagged images; readiness gate before traffic cutover; automated rollback to the last good SHA | Traffic never reaches the bad version. Rollback completes in under 5 min. Migrations are backward compatible for the release window | I1, I2 | S1 | CI/CD pipeline (Day 12) |
| **F-20** | Resource leak | 30-60 min mixed-traffic soak | Bounded pools, bounded batches, graceful shutdown | Flat memory, thread, connection, and queue-depth curves. No steady climb toward exhaustion | — | S2 | Soak test (Day 11) |

---

## 4. Detailed containment — the six cases the design must answer

The Day 1 gate is the ability to explain these before writing code.

### F-01 · Duplicate POST

Two identical reserve requests arrive at two different replicas at the same millisecond.
Both try to insert a reservation carrying the same `Idempotency-Key`.

PostgreSQL's unique index does the arbitration: the second insert **blocks** on the index
until the first transaction resolves, so the outcome is decided by the database, not by
timing luck in the JVM.

- First transaction commits: the second insert raises a unique violation. That request rolls
  back, re-reads the winning reservation by key, and returns it with
  `Idempotency-Replayed: true`. **One hold.**
- First transaction rolls back (sold out, cap exceeded, crash): the second insert succeeds
  and becomes the winner. **One hold.**
- The wait exceeds `lock_timeout`: `409 IDEMPOTENCY_IN_PROGRESS` with `Retry-After`. A safe,
  retryable answer — never a hung connection.

Nothing here depends on application-level coordination, which is exactly why it survives
adding replicas.

### F-02 · Client timeout after commit

The most under-designed failure in booking systems. The transaction committed, inventory
moved, and the response never reached the client. The client does not know whether it holds
a ticket.

The system does not try to solve this by guessing. It makes the ambiguity **resolvable**:

1. `Idempotency-Key` is mandatory on reserve, so a retry is unambiguously the same logical
   request rather than a new one.
2. The retry path returns the original outcome (F-01), so the client learns the truth.
3. `GET /reservations/{id}` and listing by key let a client reconcile without mutating
   anything.
4. If the client never retries, the hold expires on TTL and inventory returns automatically
   (**I4**). A lost response costs at most one TTL of held inventory, never a permanently
   leaked ticket.

The design consequence: **the client's timeout is never allowed to become the server's
correctness problem.**

### F-11 · Two replicas racing

There is no application-level lock, no leader election, and no in-JVM synchronisation on the
booking path — so there is nothing that breaks when a second replica appears.

Every mutation that touches inventory acquires the same PostgreSQL row lock, and that lock
is global to the database, not to the process. Replica 2's `SELECT ... FOR UPDATE` waits for
replica 1's transaction exactly as a second thread inside replica 1 would. The concurrency
model is identical at 1 replica and at N, which is why the Day 9 acceptance gate is that
multi-instance results are *indistinguishable* from single-instance results.

The corollary is a rule with teeth: **any future use of `synchronized`, a local cache, or an
in-memory counter on the booking path is a correctness bug**, because it is invisible to the
other replica.

### F-12 · App crash

A replica is killed mid-request. Whatever it was doing was either committed or not — there
is no in-between, because all inventory movement happens in a single transaction with no
external side effects inside it.

- Uncommitted transactions roll back; PostgreSQL releases the row locks it held, so waiting
  requests on the surviving replica proceed within `lock_timeout`.
- Committed holds are durable and owned by no particular replica. They confirm from the
  other replica or expire on TTL.
- The load balancer removes the dead replica on health-check failure; graceful shutdown lets
  a *planned* stop drain in-flight requests first.

This works precisely because nothing important lived in that JVM's memory.

### F-13 · Database restart

PostgreSQL is a single point of failure for availability, and that is an accepted, explicit
trade for v1.0 (see [ADR-001](adr/ADR-001-postgresql-inventory-authority.md)). What is not
acceptable is *incorrectness* during the outage.

- In-flight transactions abort. Because every inventory move is one transaction, an abort
  can never leave `available` decremented without a matching hold — atomicity does the work.
- The readiness probe fails while the database is unreachable, so Nginx stops sending
  traffic to a replica that cannot serve it safely. Liveness stays green, so nothing
  pointlessly restarts a process that is merely waiting.
- Recovery is automatic: the pool re-establishes connections, readiness goes green, traffic
  returns. No manual step, no cache to warm, no state to rebuild.
- **RPO = 0** for committed transactions.

### F-08 · Broker outage

RabbitMQ is deliberately off the correctness path, so its outage is an async-lag problem
rather than a booking problem.

Publishing to the broker inside the booking transaction would be the classic dual-write bug:
the database commit and the broker publish cannot be atomic, so a crash between them either
loses an event or announces a booking that was rolled back. The outbox removes the dual
write entirely — the event is *data written by the same transaction*, and publishing is a
separate, retryable step.

During an outage: bookings and confirmations succeed normally; `outbox_event` accumulates
unpublished rows; the oldest-unpublished-age gauge climbs and alerts (F4 in
[slo.md](slo.md)). After recovery the publisher drains the backlog, marking rows published
only on broker ack. Consumers dedupe on `event_id`, so redelivery during the messy recovery
window is a no-op (F-21).

---

## 5. Verification schedule

| Day | Drills and tests |
|---|---|
| Day 3 | F-03, F-16 — concurrency tests with a start barrier on real PostgreSQL |
| Day 4 | F-01, F-02, F-05, F-06, F-18 — idempotency, expiry, cancellation races |
| Day 5 | F-09 — Redis outage with booking traffic running |
| Day 6 | F-03, F-04 — k6 Gate 1/2 with post-run reconciliation |
| Day 7 | F-08, F-10, F-21 — outbox, DLQ, consumer idempotency |
| Day 8 | F-02, F-12, F-13, F-14, F-17 — full failure-drill session |
| Day 9 | F-11, F-15 — multi-instance and pool saturation under k6 Gate 3 |
| Day 11 | F-05, F-06, F-07, F-20 — full concurrency matrix plus soak |
| Day 12 | F-19 — deploy, readiness gate, rollback, production smoke test |

Every drill records: what was induced, what was observed (client responses, metrics, logs),
whether it matched this document, and the reconciliation result afterwards. **A drill whose
outcome differs from the expected behaviour above updates this document** — the matrix is a
living record of measured behaviour, not a wish list.

---

## 6. Escalation rule

If any drill produces an S0 outcome — an oversell, a double release, a double confirm, or a
cap bypass — work stops. The sequence is fixed:

1. Capture the exact reproduction (seed, VUs, timing, logs, database state).
2. Write a failing automated test that reproduces it **before** attempting a fix.
3. Fix, then confirm the test passes and the reconciliation query returns zero rows.
4. Update this matrix with what was actually observed.

A concurrency bug that is fixed without a regression test that failed first has not been
fixed — it has been disturbed.
