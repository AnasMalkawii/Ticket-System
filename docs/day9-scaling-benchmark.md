# Day 9 - Horizontal scale and hot-row optimization

## Outcome

The Day 9 acceptance gate passed in all six measured cases: two stateless Spring Boot
replicas, one PostgreSQL primary, 500/1,000/2,000 virtual users, exactly 100 holds from 100
tickets, no negative counter, and zero reconciliation drift.

The conditional atomic inventory update won the head-to-head comparison and is now the
default reservation strategy. The original pessimistic branch remains selectable with
`INVENTORY_STRATEGY=PESSIMISTIC` so the comparison stays reproducible.

## Local multi-instance deployment

`deploy/day9/docker-compose.yml` starts:

- Nginx with least-connections balancing and no session affinity;
- two identical, stateless application replicas;
- one PostgreSQL 16 primary with `max_connections=50`;
- Redis 7 for the read-heavy catalog cache.

The harness obtained a JWT from replica 1 and successfully used it against replica 2,
proving that authentication does not depend on an HTTP session. Nginx response headers
showed both upstream addresses during each strategy run.

Each replica has a Hikari maximum of 8 and minimum idle of 2. The two application pools can
therefore consume at most 16 of PostgreSQL's 50 connections (32%), leaving 34 connections
for migrations, administration, monitoring, and recovery.

## Catalog read path

- Event details, projected event pages, and advisory availability use bounded Redis TTLs.
- `GET /api/v1/events` validates `page` and `size`, caps a page at 100 rows, and returns a
  stable DTO rather than a persistence entity.
- Catalog pages now use a closed projection, so `created_at` and `updated_at` are not loaded.
- Migration V8 adds covering indexes for filtered and unfiltered page order, including the
  UUID tie-breaker required for deterministic pagination.
- `SchemaQueryPlanIT` runs `EXPLAIN ANALYZE` on both list shapes and requires the matching
  index-only scan with no separate sort.

## Benchmark method

Run `20260905-local4` used the same machine and fixture for both strategies. Every case
targeted one newly created event with 100 tickets. All requested VUs stayed active, while
request starts were distributed over two seconds to avoid measuring a single-millisecond
SYN backlog in Docker Desktop's Windows port proxy. That spread is still much faster than
the serialized hot-row path and maintained database contention.

`Useful responses/s` counts completed `201 Created` and `409 SOLD_OUT` responses, excluding
timeouts and infrastructure failures. This is more meaningful than raw completed
iterations/s when one strategy produces more failed requests.

| Strategy | VUs | p50 ms | p95 ms | p99 ms | Useful responses/s | Faults | Peak Hikari pending | Peak DB lock waiters |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Pessimistic | 500 | 2,765.83 | 3,763.51 | 4,069.56 | 71.80 | 6 (1.2%) | 374 | 15 |
| Atomic | 500 | 2,069.01 | 3,065.07 | 3,366.22 | 83.83 | 16 (3.2%) | 384 | 12 |
| Pessimistic | 1,000 | 3,019.04 | 4,225.02 | 4,998.73 | 136.66 | 194 (19.4%) | 830 | 14 |
| Atomic | 1,000 | 2,122.09 | 3,153.26 | 3,509.46 | 211.87 | 59 (5.9%) | 739 | 15 |
| Pessimistic | 2,000 | 3,095.34 | 5,005.18 | 5,020.38 | 90.54 | 1,339 (67.0%) | 1,878 | 15 |
| Atomic | 2,000 | 3,044.85 | 4,291.23 | 4,380.99 | 224.23 | 779 (39.0%) | 1,848 | 15 |

Against the pessimistic branch, the atomic update improved p95 by 18.6%, 25.4%, and 14.3%
at 500, 1,000, and 2,000 VUs. Raw completed-iteration throughput increased by 19.2%, 32.8%,
and 34.1%. Fault counts are noisy at this local scale: atomic produced 10 more faults at 500
VUs, then 69.6% and 41.8% fewer at 1,000 and 2,000 VUs. The decision is based on the
consistent p95 and throughput wins, not that noisy secondary count.

## Correctness evidence

Every one of the six independent fixtures ended in the same authoritative state:

| Total | Available | Held | Sold | Reservation rows | Conservation drift | Held drift | Sold drift |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 100 | 0 | 100 | 0 | 100 | 0 | 0 | 0 |

The atomic statement uses `WHERE available >= :quantity`; PostgreSQL acquires the row lock
inside the update and re-evaluates that condition after a competing transaction commits.
The per-user cap is checked while that same lock is still held. A cap failure rolls back
both the inventory movement and the newly inserted reservation.

## Decision and remaining limit

The application keeps the atomic strategy because it improved p95 and throughput at every
load level while preserving all invariants and domain errors. Optimistic locking was not
implemented: a deliberately hot row would turn it into a retry storm.

The 300 ms p95 objective is still not met. Both branches ultimately serialize on one
PostgreSQL row, and at 2,000 local VUs the client, Docker port proxy, application pools, and
database are all saturated. This is a documented capacity limit, not a correctness failure;
sharding remains unjustified until a production-like environment proves a single database
insufficient.

## Reproduce and inspect

```powershell
.\load-tests\run-day9.ps1
```

Raw summaries, console output, telemetry, reconciliation, and machine metadata are stored
in `load-tests/results/day9-20260905-local4/`.

Final verification: **122 tests passed, 0 failures, 0 errors, 0 skipped**.
