# High-traffic availability and p95 improvement

**Final validation:** 2026-09-07  
**Availability verdict:** **PASS in all three requested burst tests.**  
**Latency verdict:** **materially improved, but the strict 300 ms p95 gate is not yet met.**

## Outcome

The final implementation completed all 11,500 measured reservation requests with HTTP 201.
There were no business rejections, server or transport faults, ambiguous commits, connection
pool timeouts, gateway 499/502/503 responses, service restarts, or inventory drift.

| Workload | Availability | p50 | p95 | p99 | Throughput |
|---|---:|---:|---:|---:|---:|
| 500 VUs / 500 requests | 100.00% | 309.70 ms | 552.62 ms | 616.80 ms | 171.77/s |
| 1,000 VUs / 1,000 requests | 100.00% | 696.83 ms | 786.25 ms | 801.58 ms | 273.29/s |
| 500 VUs / 10,000 requests | 100.00% | 213.79 ms | 431.87 ms | 530.89 ms | 1,392.30/s |

## Improvement over the accepted availability build

| Workload | Previous p95 | New p95 | p95 reduction | Throughput change |
|---|---:|---:|---:|---:|
| 500 / 500 | 1,475.80 ms | 552.62 ms | 62.6% faster | +47.6% |
| 1,000 / 1,000 | 2,995.25 ms | 786.25 ms | 73.8% faster | +48.6% |
| 500 / 10,000 | 1,285.95 ms | 431.87 ms | 66.4% faster | +105.4% |

The steady, already-warm 500-VU / 10,000-request diagnostic reached 323.52 ms p95
and 2,431 reservations per second. The table above deliberately reports the stricter
fresh-stack results, which include first-traffic JVM and connection costs.

## Why requests were waiting

The database connection pool was not the limiting queue: the final 10,000-request run peaked
at four active connections out of eight and zero pending callers. The long tail came from four
effects in the synchronous request path:

1. Every request waits for a durable reservation result before HTTP 201 can be returned.
2. The old event batcher paid its 50 ms coalescing delay again for every backlog batch.
3. Each accepted request produced separate ORM reservation and outbox inserts while the hot
   inventory transaction was open.
4. A fresh two-replica stack compiled and logged heavily while also accepting hundreds of new
   connections at once.

## What changed

- The event batcher pays the coalescing window once, then drains an existing backlog without
  adding another artificial sleep.
- Validation, fingerprints, payload serialization, and UUID generation happen before the
  authoritative inventory-row lock is taken.
- Reservations and outbox events are persisted with two set-based PostgreSQL inserts per batch.
  The inventory counters remain one authoritative locked update in the same transaction.
- Application-generated UUIDv7 identifiers make the native batch insert path possible while
  preserving time-ordered IDs.
- The burst profile warms the real batch transaction before startup completes, avoids duplicate
  request-thread fingerprint work, uses PostgreSQL batched-insert rewriting, and avoids a cold
  high-tier compiler burst during the first traffic wave.
- Nginx retains the full access trail but buffers writes to Docker's log pipe, and continues to
  balance across the two healthy replicas with `least_conn`.

## Correctness and failure checks

| Workload | Held | Reservation rows | Available | Conservation drift | Held drift | Sold drift |
|---|---:|---:|---:|---:|---:|---:|
| 500 / 500 | 500 | 500 | 0 | 0 | 0 | 0 |
| 1,000 / 1,000 | 1,000 | 1,000 | 0 | 0 | 0 | 0 |
| 500 / 10,000 | 10,000 | 10,000 | 0 | 0 | 0 | 0 |

The reservation API and batch integration checks passed 19/19, including replay, per-user cap,
sold-out behavior, outbox creation, and inventory conservation. All 33 unit tests passed. A
broader Docker-hosted integration run passed 98 of 99 checks; the unrelated catalog cache check
could not initialize Redis through Docker Desktop within its 300 ms client timeout. Its failure
occurred before exercising the reservation changes. The same host also has a Java NIO loopback
failure when that test is run directly on Windows.

## Assessment and remaining limit

The change fixes the multi-second queueing and more than doubles sustained booking throughput
without trading away availability or correctness. It does not make the current 300 ms p95 gate
pass for an instantaneous cold burst on this single local Docker host, so `goodOverall` remains
false in the raw result files.

Reaching a hard 300 ms p95 for 1,000 simultaneous new connections would require a larger design
or capacity change: asynchronous `202 Accepted` admission with status polling, independently
locked inventory partitions, or production-grade horizontal capacity. Those alter the API or
inventory architecture and should not be hidden as another timeout adjustment.

Observed 100% availability across 11,500 requests is strong test evidence, not proof of a
sustained 99.99% production SLO. That claim still requires a longer production-like measurement
window and continuing failure-injection tests.

## Raw evidence

- [500 VUs / 500 requests](../load-tests/results/high-traffic-p95fix-finalcandidate-500x500/result.json)
- [1,000 VUs / 1,000 requests](../load-tests/results/high-traffic-p95fix-finalcandidate-1000x1000/result.json)
- [500 VUs / 10,000 requests](../load-tests/results/high-traffic-p95fix-finalcandidate-500x10000/result.json)
- [Historical availability failure baseline](high-traffic-500vu-10000-report.md)
