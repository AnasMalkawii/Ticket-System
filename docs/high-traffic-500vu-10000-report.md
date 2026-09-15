# High-traffic test: 500 VUs / 10,000 reservation requests

> Historical failure baseline. The current implementation and final retest are documented
> in [high-traffic-availability-improvement.md](high-traffic-availability-improvement.md).

**Run:** `20260906-internal1`  
**Verdict:** **FAIL — not good enough for production at this burst level.**

## Workload and environment

- 500 concurrent k6 virtual users.
- Exactly 10,000 reservation requests, plus 3 excluded setup requests.
- One hot event with 10,000 tickets.
- One unique user and idempotency key per reservation attempt.
- Nginx in front of two stateless Spring Boot replicas.
- HikariCP pool size 8 per replica (16 total), PostgreSQL 16 single primary.
- k6 ran on the internal Docker network. This avoids the Windows published-port refusal
  observed in two preliminary host-side runs and makes the application/gateway result the
  canonical measurement.

This is an immediate flash-sale burst. It does not spread 10,000 requests across a fixed
time window.

## Result

| Measurement | Actual | Target | Result |
|---|---:|---:|---|
| Reservation attempts | 10,000 | 10,000 | Pass |
| Client-visible reservation successes | 140 | — | — |
| Server/transport failures | 9,860 (98.6%) | <= 0.1% | Fail |
| Availability | 1.4% | >= 99.9% | Fail |
| Reserve p95 | 1,026.90 ms | <= 300 ms | Fail |
| Reserve p99 | 4,080.40 ms | <= 800 ms | Fail |
| Successful reservation rate | 18.69/s | — | — |
| All completed attempts rate | 1,334.86/s | — | Misleading: mostly fast failures |

The expected-response latency distribution (140 acknowledged reservations plus the 3 setup
responses) was p50 3,129.76 ms, p95 4,830.66 ms, and p99 4,928.00 ms. The much higher
"completed attempts" rate is caused by the gateway rejecting most requests quickly; it is
not useful booking throughput.

## What was good

- Inventory never oversold: `available` remained non-negative.
- Inventory conservation passed with zero conservation, held-ledger, and sold-ledger drift.
- All 10,000 client outcomes were counted; there were no missing k6 iterations.
- Nginx, both application replicas, and PostgreSQL remained running, had zero restarts/OOM
  kills, and were healthy again immediately after the burst.
- Both replicas participated, and the system's mandatory idempotency keys provide a safe way
  for clients to resolve timed-out requests by retrying the same key.

## What failed and why

Authoritative reconciliation found 184 committed holds and 9,816 available tickets, with
zero drift. k6 received only 140 success responses, leaving 44 committed reservations whose
clients did not receive a success acknowledgement. That is not overselling, but it is an
ambiguous client outcome and fails the end-to-end correctness gate for this run.

The measured bottleneck was database connection saturation:

| Evidence | Measurement |
|---|---:|
| Hikari active | 16 / 16 |
| Peak Hikari callers pending | 386 |
| Peak PostgreSQL active sessions | 14 |
| App 1 Hikari acquisition timeouts | 156 |
| App 2 Hikari acquisition timeouts | 105 |

The gateway then amplified the overload. Its reservation status mix was 141 `201`, 45
client-closed `499`, 9,554 `502`, and 260 `503`. Nginx recorded 120 upstream timeouts; with
both upstreams configured as failed after only two failures for three seconds, it recorded
9,555 `no live upstreams` errors and rejected most of the remaining burst immediately.

## Assessment

The result is **not good**. The zero-drift inventory invariant is a meaningful success, and
the services recovered without crashing, but 1.4% availability and multi-second tail
latency are far outside the accepted SLO. The current two-replica local configuration must
not be described as supporting a 500-VU, 10,000-request flash-sale burst.

This is a single-workstation Docker measurement, not a production capacity certificate.
The most useful next validation is to tune overload handling so Nginx does not eject both
healthy-but-busy replicas, add deliberate admission control/backpressure before the database
pool, and then rerun this exact scenario unchanged.

## Evidence

- Raw computed result: `load-tests/results/high-traffic-20260906-internal1/result.json`
- Raw k6 summary: `load-tests/results/high-traffic-20260906-internal1/k6-summary.json`
- k6 console output: `load-tests/results/high-traffic-20260906-internal1/k6-console.log`
- One-second connection telemetry: `load-tests/results/high-traffic-20260906-internal1/telemetry.csv`
- Accepted thresholds: `docs/slo.md`
