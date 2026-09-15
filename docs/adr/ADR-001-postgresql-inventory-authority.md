# ADR-001: PostgreSQL is the sole authority for ticket inventory

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-08-22 (Week 1, Day 1) |
| **Deciders** | Backend |
| **Supersedes** | — |
| **Related** | [ADR-002](ADR-002-pessimistic-locking.md) · [architecture.md](../architecture.md) · [slo.md](../slo.md) · [failure-matrix.md](../failure-matrix.md) |

---

## Context

The system sells a fixed number of tickets per event under flash-sale contention: hundreds
of concurrent requests competing for 100 tickets. The single defining requirement is that
**101 tickets can never be sold from an inventory of 100**, under any interleaving, any
retry pattern, or any partial failure.

That requirement forces an early, load-bearing decision: *which store is allowed to say
whether a ticket exists?* Every other component — cache, broker, application replica — must
then be designed as unable to contradict it.

The tempting alternative is Redis. It is fast, `DECR` is atomic, and its throughput on a
single hot key dwarfs a PostgreSQL row lock. In a flash sale that is exactly the workload
shape, and the pull toward "just decrement in Redis" is strong.

### Decision drivers

1. **Correctness under contention outranks throughput.** A fast wrong answer is worthless.
2. **Inventory changes must be atomic with reservation creation.** Decrementing a counter
   and recording *who* holds the ticket cannot be two independently-failing steps.
3. **Durability.** A committed hold must survive process death, restart, and eviction.
4. **The system must be provably correct**, ideally by mechanisms a reviewer can verify
   (constraints, transaction boundaries) rather than by reasoning about timing.
5. **Two-week budget.** The mechanism must be implementable and testable in the time
   available, not merely theoretically sound.

---

## Considered options

### Option A — PostgreSQL is authoritative *(chosen)*

Inventory counters live in `ticket_inventory`. Every mutation happens inside a database
transaction that also creates or transitions the reservation. Redis may cache catalog data
and displayed availability, but never authorises a decrement.

- **For:** ACID atomicity across counter and reservation in one commit; durability; database
  `CHECK` constraints make `available < 0` unrepresentable rather than merely unlikely;
  correctness is verifiable by reading constraints and transaction boundaries; behaviour is
  identical with N replicas; trivially testable with Testcontainers against real PostgreSQL.
- **Against:** Throughput on a single hot row is bounded by the serialised critical section;
  the database is a single point of failure for availability; scaling one hot event
  eventually requires deliberate work (bucketing, sharding).

### Option B — Redis is authoritative, PostgreSQL is a follower

`DECR` on a Redis key gates the sale; PostgreSQL is written asynchronously for reporting.

- **For:** Very high throughput on a hot key; low latency; no row-lock queueing.
- **Against:** **The decrement and the reservation write become a dual write.** A crash
  between them either sells a ticket nobody holds or holds a ticket nobody paid for, and
  there is no transaction to undo it. Recovering the true count after a Redis restart,
  failover, or eviction means reconstructing it from PostgreSQL — which is only correct if
  PostgreSQL was authoritative all along, contradicting the premise. Redis persistence is
  asynchronous by default, so an acknowledged `DECR` can be lost on failover. Lua scripting
  makes the Redis side atomic but does nothing about the cross-store boundary.
- **Verdict:** Rejected. It optimises the metric we are not judged on, and makes the metric
  we *are* judged on unprovable.

### Option C — Redis as a fast gate in front of PostgreSQL

Redis performs an optimistic pre-check, PostgreSQL confirms authoritatively.

- **For:** Sheds obviously-doomed load once an event is sold out.
- **Against:** Two sources of truth that must be reconciled after every failure. Redis must
  be replenished when a database transaction rolls back — another dual write, with the same
  crash window. Adds a distributed-state problem to buy an optimisation not yet shown to be
  needed.
- **Verdict:** Rejected for v1.0. A *stateless* fast rejection using a cached sold-out flag
  is acceptable later, precisely because a stale flag can only cause a false rejection
  (safe), never a false acceptance (unsafe).

### Option D — Application-level coordination (in-JVM locks)

`synchronized` or a JVM lock around the reserve path.

- **For:** Trivially simple on one instance.
- **Against:** Breaks completely the moment a second replica exists — two JVMs cannot see
  each other's locks. It would pass every single-instance test and then fail silently in
  production. Directly contradicts the stateless-replica requirement.
- **Verdict:** Rejected. This is the failure mode the project exists to demonstrate
  understanding of.

---

## Decision

**PostgreSQL is the single source of truth for ticket inventory. All inventory mutations
occur inside a PostgreSQL transaction that also records the corresponding reservation state
change. No other store may authorise the creation or release of a ticket.**

Concretely:

1. `ticket_inventory` holds `total`, `available`, `held`, `sold` per event, and is the only
   place a ticket count is decided.
2. Every mutation of those counters happens in a transaction that, in the same commit, also
   creates or transitions the `reservation` row explaining the movement.
3. Database `CHECK` constraints enforce `available >= 0`, `held >= 0`, `sold >= 0`, and
   `available + held + sold = total`. **The invariant survives a bug in the service layer** —
   a violating transaction fails to commit rather than committing bad data.
4. `UNIQUE` constraints enforce idempotency (`reservation.idempotency_key`) and
   single confirmation (`ticket_order.reservation_id`) in the database, not in application
   memory.
5. Redis may cache the event catalog and a **display-only** availability figure, explicitly
   flagged `advisory: true` in API responses. It is never read inside the reserve
   transaction.
6. The application performs **no** in-memory coordination on the booking path — no
   `synchronized`, no local counters, no per-instance caches of availability.

---

## Consequences

### Positive

- Overselling becomes structurally hard rather than a race to be reasoned about: it would
  require defeating both a row lock and a `CHECK` constraint.
- Atomicity is free. A crash at any point leaves a valid state, because there is one commit.
- Correctness is independent of replica count — the containment argument for "two replicas
  racing" is the same argument as for two threads ([F-11](../failure-matrix.md)).
- Recovery is trivial: no cache to rebuild, no counters to reconcile, RPO = 0.
- Testable with real infrastructure via Testcontainers; the tests exercise the same engine
  that runs in production.
- A Redis or RabbitMQ outage degrades speed or freshness, never correctness
  ([F-08](../failure-matrix.md), [F-09](../failure-matrix.md)).

### Negative — accepted, with mitigations

- **Throughput ceiling per hot event.** Reservations for one event serialise on one row.
  *Mitigation:* keep the critical section minimal (no network calls, no payment, no publish
  inside the lock); measure it honestly on Day 6; document the ceiling rather than hide it.
- **PostgreSQL is a single point of failure for availability.** *Mitigation:* explicitly
  accepted for v1.0. Bounded timeouts and readiness gating make an outage fast, visible, and
  self-healing rather than a hang ([F-13](../failure-matrix.md)). Replication is a post-v1.0
  concern.
- **Higher latency than a Redis counter.** *Mitigation:* the latency budget
  ([L1](../slo.md)) is set with this in mind, and lock wait is measured separately (L6) so
  the cost is quantified rather than assumed.
- **Connection pressure.** Every reserve needs a pooled connection. *Mitigation:* a bounded,
  intentionally sized HikariCP pool with fail-fast acquisition
  ([F-15](../failure-matrix.md)).

---

## Compliance

This ADR is enforceable, not aspirational:

- Code review rejects any read of inventory counters from Redis inside a write path.
- Code review rejects `synchronized`, `ReentrantLock`, or static mutable state on the
  booking path.
- `CHECK` constraints in the Flyway migrations are the mechanical backstop.
- The reconciliation query in [slo.md](../slo.md) runs after every load scenario, and the
  `oversell_detected_total` metric is a permanent tripwire that must never leave zero.
- The two-replica scenario ([F-11](../failure-matrix.md)) is part of the release gate, so a
  regression toward instance-local state fails the build instead of shipping.

---

## Revisit triggers

Reopen this decision if any of the following is **measured**, not anticipated:

- A single hot inventory row is proven to be the throughput bottleneck at a load the product
  actually requires, *after* the critical section has been minimised and inventory bucketing
  has been tried.
- Availability requirements grow to need multi-region writes, which a single-primary
  PostgreSQL cannot serve.
- A workload appears where the authoritative decision genuinely does not need to be
  transactional with a durable record — none exists in a ticket sale.

Until then the burden of proof rests on any proposal to move inventory authority out of
PostgreSQL, and that proof must explain how the resulting dual write is made atomic.
