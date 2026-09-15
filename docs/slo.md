# Service Level Objectives

| | |
|---|---|
| **Status** | Accepted — baseline for v1.0.0 |
| **Last updated** | 2026-08-22 (Week 1, Day 1) |
| **Applies to** | Ticket Booking System v1.x |
| **Related** | [architecture.md](architecture.md) · [failure-matrix.md](failure-matrix.md) |

---

## 1. How to read this document

These targets are written **before implementation**, so that later performance work is
judged against a number agreed in advance rather than against whatever the system happens to
produce. They fall into two classes, and the distinction matters:

- **Hard invariants (section 3)** — zero tolerance. There is no error budget. One violation
  is a release blocker, not a statistic.
- **Statistical objectives (sections 4-7)** — latency, availability, and lag. These consume
  an error budget and are allowed to be missed within it.

Conflating the two is the classic mistake in a booking system: 99.9% correct inventory is
not "three nines", it is a broken product. Availability and latency are negotiable under
load; **inventory conservation never is**.

Every number below is measured, not asserted. Section 8 states exactly how.

---

## 2. Measurement environment

A latency target without a stated environment is meaningless. All objectives are defined
against this reference environment, and any published result must restate it:

| Property | Reference value |
|---|---|
| Deployment | Docker Compose, 2 app replicas behind Nginx |
| App | Java 21, Spring Boot 4.x, HikariCP pool 8/replica |
| Database | PostgreSQL 16, single primary, local volume |
| Cache / broker | Redis 7, RabbitMQ 3.x |
| Load generator | k6, run from a separate process on the same host |
| Test fixture | One hot event seeded with exactly 100 tickets |
| Hardware | Recorded per run in `results.md` (CPU model, cores, RAM, disk type) |

The load generator competes with the system under test for CPU on a single-host setup. Any
result where the client is the bottleneck must say so explicitly rather than being reported
as a server limit. When the deployment target changes, these numbers are **re-baselined, not
reused**.

---

## 3. Correctness objectives — zero tolerance

| ID | Objective | Target | Measurement |
|---|---|---|---|
| **C1** | Oversold tickets | **exactly 0**, always | Post-run reconciliation: `available >= 0` and `available + held + sold = total` |
| **C2** | Duplicate `Idempotency-Key` creates a second hold | **0 occurrences** | 10-50 identical retries produce exactly 1 reservation |
| **C3** | A hold released more than once | **0 occurrences** | `held` never goes negative; each reservation has exactly one terminal transition |
| **C4** | A reservation confirmed more than once | **0 occurrences** | `UNIQUE(reservation_id)` on `ticket_order`; `sold` increments once per hold |
| **C5** | Per-user/event cap exceeded | **0 occurrences** | Sum of active + confirmed qty per user per event never exceeds 4 |
| **C6** | Correctness differs between 1 replica and 2 | **0 differences** | Same scenario, both topologies, identical reconciliation result |

**Enforcement.** C1-C6 are asserted by automated tests (Testcontainers concurrency suites)
and re-asserted by the k6 reconciliation step after every load run. The CI pipeline fails
the build on any violation, so a violating commit cannot produce a deployable image.

The reconciliation query run after every scenario:

```sql
SELECT event_id, total, available, held, sold,
       (available + held + sold) - total AS drift
FROM ticket_inventory
WHERE (available + held + sold) <> total
   OR available < 0 OR held < 0 OR sold < 0;
-- Zero rows required. Any row = release blocker.
```

---

## 4. Latency objectives

Measured server-side (Micrometer HTTP timers) and client-side (k6), at **500 concurrent
virtual users** against the reference environment. Client-side numbers are the ones
published, because they include queueing the server does not see.

| ID | Operation | Target | Threshold |
|---|---|---|---|
| **L1** | `POST /events/{id}/reservations` | **p95 <= 300 ms** | p99 <= 800 ms |
| **L2** | `POST /reservations/{id}/confirm` | p95 <= 400 ms | p99 <= 1000 ms (includes mock payment) |
| **L3** | `DELETE /reservations/{id}` | p95 <= 250 ms | p99 <= 700 ms |
| **L4** | `GET /events/{id}` (cache hit) | p95 <= 50 ms | p99 <= 150 ms |
| **L5** | `GET /events` (catalog list) | p95 <= 120 ms | p99 <= 300 ms |
| **L6** | Inventory lock acquisition wait | p95 <= 150 ms | p99 <= 500 ms |

**Rejections count toward latency.** A `409 SOLD_OUT` is a real user-facing response and is
included in the latency distribution. Excluding fast rejections would flatter the numbers
exactly when the system is most stressed.

**L6 is the diagnostic metric.** If L1 is missed, L6 says whether the cause is lock
contention (the expected bottleneck) or something else — pool saturation, GC, CPU. This is
the difference between "p95 was 900 ms" and "p95 was 900 ms because lock wait was 780 ms of
it at 1,000 VUs".

**Miss policy.** If L1 is missed at 500 VUs, the release is not blocked, but the load-test
report **must** identify the measured bottleneck with evidence (lock wait percentiles,
HikariCP `pending` gauge, `pg_stat_activity` wait events, CPU saturation). A missed target
with a documented cause is acceptable engineering. A missed target with a guess is not.

---

## 5. Availability and error budget

### 5.1 What counts as an error

This is the most important definition in the document.

| Response class | Counts against availability? | Why |
|---|---|---|
| `2xx` | No | Success |
| `409 SOLD_OUT`, `SALE_NOT_STARTED`, `USER_LIMIT_EXCEEDED`, `INVALID_STATE`, `RESERVATION_EXPIRED` | **No** | Correct business answers. The system worked. |
| `400`, `401`, `403`, `404` | **No** | Client-side faults |
| `429 RATE_LIMITED` | **No** (tracked separately) | Deliberate protection working as designed |
| `5xx` | **Yes** | Server fault |
| Connection error, timeout, socket reset | **Yes** | Server fault from the client's perspective |
| Request exceeding 5 s | **Yes** | Treated as failed even if it eventually returns |

A sold-out flash sale drives a huge spike in `409 SOLD_OUT`. That is the system succeeding
under its hardest condition and must never burn error budget. The corollary is that a 5xx
storm during sell-out is a **severe** failure, because the cheap correct answer was
available and the system produced garbage instead.

### 5.2 Targets

| ID | Objective | Target | Window |
|---|---|---|---|
| **A1** | Reservation write API availability (non-5xx / total) | **99.9%** | 30 days rolling |
| **A2** | Catalog read API availability | 99.5% | 30 days rolling |
| **A3** | Availability during a k6 run at 500 VUs | 99.9% | per run |
| **A4** | Availability during a rolling deploy | **100%** (zero-downtime) | per deploy |

A1 at 99.9% over 30 days permits **43 min 12 s** of full unavailability, or an equivalent
partial error rate. A4 is absolute within the deploy window: readiness gating plus draining
means a deploy should be invisible to clients, so a single 5xx during rollout is treated as
a defect in the deployment procedure.

### 5.3 Burn-rate alerting

Alert on how fast the budget is being consumed, not on raw error rate — a 1% error rate for
one minute and for one day are very different problems:

| Severity | Burn rate | Detection window | Budget consumed |
|---|---|---|---|
| Page | 14.4x | 5 min (and 1 h) | 2% in 1 hour |
| Page | 6x | 30 min (and 6 h) | 5% in 6 hours |
| Ticket | 3x | 2 h (and 1 day) | 10% in 1 day |
| Ticket | 1x | 6 h (and 3 days) | 10% in 3 days |

---

## 6. Freshness and lag objectives

Asynchronous work is allowed to be late; it is not allowed to be lost or to be applied
twice.

| ID | Objective | Target | Notes |
|---|---|---|---|
| **F1** | Expired hold released after `expires_at` | p95 <= 15 s, p99 <= 60 s | Worker interval 10 s; inventory is not returned instantly by design |
| **F2** | Outbox event published after commit (broker healthy) | p95 <= 5 s, p99 <= 30 s | Publisher polls every 1 s |
| **F3** | Outbox drain after a broker outage ends | 100% published within 15 min | Bounded by backlog size and publish throughput |
| **F4** | Oldest unpublished outbox row age (steady state) | < 60 s | Primary async-health signal |
| **F5** | Messages in the DLQ (steady state) | **0** | Any DLQ message is investigated, never silently discarded |
| **F6** | Duplicate side effects from redelivery | **0** | Consumers dedupe on `event_id` via `processed_event` |

F1 has a real product consequence worth stating: after a mass abandonment, tickets return to
the pool in up to a minute, not instantly. That is a deliberate trade — an instant-release
design would require doing expiry work on the hot path, lengthening the critical section for
every reserving user.

---

## 7. Recovery objectives

| ID | Scenario | Objective | Target |
|---|---|---|---|
| **R1** | One app replica killed under load | Remaining replica serves; no committed state lost | 0 lost/duplicated inventory; recovery < 30 s |
| **R2** | PostgreSQL restart | App recovers without manual intervention | Readiness green < 60 s after DB accepts connections |
| **R3** | RabbitMQ outage | Bookings continue; events buffered in the outbox | 0 booking failures caused by the broker |
| **R4** | Redis outage | Bookings continue; catalog degrades to direct DB reads | 0 booking failures caused by the cache |
| **R5** | Failed deploy | Automated rollback to the previous image SHA | Rollback complete < 5 min |
| **R6** | Data loss (RPO) | Committed transactions survive an app or broker crash | **RPO = 0** for the database; backup/restore procedure documented |
| **R7** | In-flight requests during shutdown | Graceful drain, no truncated writes | 0 requests failed by a planned shutdown |

**RPO = 0 means committed transactions only.** A request that was in flight when a replica
died and never received a response has an undefined outcome from the client's perspective —
which is precisely why `Idempotency-Key` is mandatory on reserve. The client resolves the
ambiguity by retrying with the same key (F-02 in the failure matrix), and the database
resolves it authoritatively.

Every R-objective has a matching drill in [failure-matrix.md](failure-matrix.md), executed
on Day 8 and re-run before release.

---

## 8. Measurement

| Objective class | Instrument | Source |
|---|---|---|
| Correctness (C1-C6) | Reconciliation SQL + Testcontainers concurrency suites | CI, and after every k6 run |
| Latency (L1-L6) | k6 `http_req_duration` percentiles; Micrometer `@Timed` timers | k6 summary, Prometheus |
| Availability (A1-A4) | Status-code counters, split by domain `code` | Micrometer `http.server.requests` |
| Lag (F1-F6) | Custom gauges: outbox age, DLQ depth, expiry delay | Micrometer, RabbitMQ exporter |
| Recovery (R1-R7) | Scripted failure drills with recorded timings | Day 8 drill notes |

Illustrative queries the Grafana dashboard is built from:

```promql
# A1 — reservation write availability (5xx only counts as error)
1 - (
  sum(rate(http_server_requests_seconds_count{uri=~".*reservations.*",status=~"5.."}[5m]))
  /
  sum(rate(http_server_requests_seconds_count{uri=~".*reservations.*"}[5m]))
)

# L1 — reserve p95
histogram_quantile(0.95,
  sum by (le) (rate(http_server_requests_seconds_bucket{uri=~".*reservations"}[5m])))

# L6 diagnostic — lock wait p95
histogram_quantile(0.95,
  sum by (le) (rate(inventory_lock_wait_seconds_bucket[5m])))

# C1 tripwire — must always be 0
max_over_time(oversell_detected_total[24h])

# F4 — oldest unpublished outbox row
max(outbox_oldest_unpublished_age_seconds)
```

`oversell_detected_total` is a deliberate tripwire: a background reconciliation job
increments it if the invariant is ever violated in a running system. It should remain flat
at zero for the life of the project. **If it ever moves, that is the highest-severity alert
in the system** — higher than the site being down, because a wrong sale is harder to undo
than an outage.

---

## 9. Alerting summary

| Alert | Condition | Severity |
|---|---|---|
| Oversell detected | `oversell_detected_total > 0` | **Critical** |
| Error-budget burn | Multi-window burn rate (section 5.3) | Critical / Warning |
| Latency regression | Reserve p95 > 300 ms for 10 min | Warning |
| DB pool saturation | HikariCP `pending` > 0 for 5 min, or active/max > 0.9 | Warning |
| Lock wait spike | Lock wait p99 > 1 s for 5 min | Warning |
| Outbox backlog | Oldest unpublished row > 5 min | Warning |
| DLQ non-empty | DLQ depth > 0 | Warning |
| Readiness failing | Any replica not ready for 2 min | Critical |
| Expiry worker stalled | No expiry batch processed in 5 min while expired holds exist | Warning |

---

## 10. Error-budget policy

| Budget state | Action |
|---|---|
| > 50% remaining | Normal feature work |
| 10-50% remaining | Reliability work is prioritised over new features |
| < 10% remaining | Feature freeze; only fixes that reduce burn |
| Exhausted | Freeze until a post-mortem and a concrete corrective action ship |
| **Any correctness violation (C1-C6)** | **Immediate stop.** Root-cause it, add a regression test that fails without the fix, then resume |

The last row exists because correctness is not on a budget. Reliability work outranks
features whenever the budget is stressed, and a correctness violation outranks everything.

---

## 11. Out of scope for v1.0

- Per-tenant or per-customer SLAs; these are internal engineering objectives, not
  contractual commitments.
- Geographic redundancy, multi-region failover, and the RTO that would imply.
- Formal on-call rotation and paging escalation policy — alert severities above are defined
  so the routing can be added without redefining the objectives.
- Database read-replica lag objectives (no read replicas in v1.0).

---

## 12. Review cadence

Reviewed at the end of Week 1 (after the Day 6 baseline) and before the v1.0.0 release
(Day 12). Targets are revised when measurement shows them to be wrong — either
unachievably strict on the reference hardware, or so loose they permit user-visible
badness. **Revisions are recorded with the measurement that motivated them**, so the history
shows a target that moved because of evidence rather than because it was inconvenient.
