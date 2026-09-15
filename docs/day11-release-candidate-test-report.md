# Day 11 - Release-candidate test report

## Decision

**PASS with one explicit duration limit.** All correctness gates passed across the unit,
PostgreSQL, Redis, RabbitMQ, API, failure-recovery, concurrency, and measured soak suites.
The release-candidate run completed **129 automated tests** with no failures, errors, or
skips. The local soak ran for five minutes rather than the roadmap's preferred 30-60 minutes;
the reusable harness defaults to 30 minutes so that longer evidence can be collected before a
real release.

## Verification environment

| Property | Value |
|---|---|
| Verification date | 2026-09-06 |
| Application | Spring Boot 4.1.1, Java 21 |
| Test databases | PostgreSQL 16 Testcontainers; Redis 7 and RabbitMQ 4 where applicable |
| Soak topology | Nginx -> 2 stateless application replicas -> PostgreSQL/Redis/RabbitMQ |
| Observability | Prometheus samples every 5 seconds; 59 complete samples retained |
| Load generator | k6 2.1.0 on the same Windows/Docker Desktop workstation |

## Release matrix

| Risk area | Evidence | Result |
|---|---|---|
| Domain transitions and validation | Exhaustive status matrix; terminal-state guards; inventory creation, quantity, conservation, and mutation rules | **PASS** |
| PostgreSQL schema and transactions | Flyway from zero, check/unique/foreign-key constraints, reconciliation view, query-plan indexes, rollback behavior | **PASS** |
| Reserve race / zero oversell | 300 simultaneous attempts for 100 tickets, mixed quantities, per-user cap, both atomic and pessimistic inventory strategies | **PASS** |
| Duplicate idempotency key | Ten simultaneous identical reservations produce one row and one creation event; slow winner returns bounded retry guidance | **PASS** |
| Duplicate cancellation | Twenty simultaneous calls produce one `CANCELLED` transition, one release, one cancellation event, and zero drift | **PASS** |
| Duplicate confirmation | Twenty simultaneous calls produce one `CONFIRMED` transition, one order, one sold movement, one confirmation event, and zero drift | **PASS** |
| Expiry concurrency | Repeat-safe expiry, cancellation/expiry race, and two workers splitting 250 expired holds with `SKIP LOCKED` | **PASS** |
| Restart and replica-loss recovery | Abrupt connection loss rolls back uncommitted work; a real PostgreSQL container restart preserves the committed reservation and replay identity; readiness recovers | **PASS** |
| RabbitMQ and duplicates | Broker outage leaves booking committed in the outbox, recovery drains it, duplicate delivery is logically once, poison delivery reaches the DLQ | **PASS** |
| API auth and authorization | Login/token round trip, public/protected separation, owner/admin policy, malformed/expired auth, least-privilege actuator access | **PASS** |
| API validation and errors | Strict request boundaries plus stable problem code/status/correlation contract for domain and dependency failures | **PASS** |
| Idempotent API response | Identical reservation retries return the original resource and `Idempotency-Replayed: true`; conflicting fingerprints are rejected | **PASS** |
| Steady soak and leak signal | 17,846 reserve/cancel cycles, 1,790 replay checks, 37,485 HTTP requests, zero failed/unexpected responses | **PASS (5-minute bound)** |

## Automated-suite result

Command:

```powershell
mvn verify
```

| Suite | Passed | Failed | Errors | Skipped |
|---|---:|---:|---:|---:|
| Unit | 33 | 0 | 0 | 0 |
| Integration / Testcontainers | 96 | 0 | 0 | 0 |
| **Total** | **129** | **0** | **0** | **0** |

The two new Day 11 lifecycle race cases are in
`ReservationMutationConcurrencyIT`. The database-restart persistence case is in
`HealthProbeRecoveryIT`.

## Soak result

Run:

```powershell
.\load-tests\run-day11.ps1 -RunId 20260906-local2 -Duration 5m -VirtualUsers 10
```

| Metric | Result |
|---|---:|
| Completed cycles | 17,846 |
| HTTP requests | 37,485 |
| Reservations created / cancelled | 17,846 / 17,846 |
| Idempotent replays | 1,790 |
| Checks | 39,272 / 39,272 passed |
| Server faults / unexpected responses | 0 / 0 |
| HTTP p95 | 12.99 ms |
| Reserve / cancel p95 | 14.30 ms / 10.74 ms |
| Opening / closing heap-window median, both replicas | 207.67 MiB / 203.47 MiB |
| Heap-window change | -4.20 MiB; no growth signal |
| Peak combined heap | 303.21 MiB |
| Peak Hikari active / configured maximum | 4 / 16 |
| Peak and closing-window Hikari pending | 0 / 0 |
| Peak active PostgreSQL sessions | 3 |

Authoritative state after the run was `total=100, available=100, held=0, sold=0`.
All 17,846 reservation rows were `CANCELLED`, no `PENDING` row remained, and conservation,
held-ledger, and sold-ledger drift were all zero.

Evidence is retained in
[`../load-tests/results/day11-20260906-local2`](../load-tests/results/day11-20260906-local2):

- `release-metrics.json` - computed gate and final values;
- `soak-summary.json` - machine-readable k6 summary;
- `soak-telemetry.csv` - every Prometheus/PostgreSQL sample;
- `soak-console.log` - full threshold output.

## Hardening found during verification

The first launch found that Nginx's dedicated `/livez` and `/readyz` locations did not
forward the original `Host` header. Both application replicas were healthy, but the edge
probe received HTTP 400 and correctly stopped the run before traffic. The health locations
now forward the same host, forwarding, and correlation headers as application traffic, and
the two-replica health gate passes.

## Known limits

1. The recorded soak is five minutes, not the preferred 30-60 minutes. It is a useful leak
   signal, not proof against slow retention. Run the default 30-minute harness before tagging
   the final release.
2. Load generation, applications, and dependencies shared one workstation. Latencies are
   local regression evidence and are not a production capacity claim.
3. Payment remains a deterministic local mock. Real provider ambiguity and provider-level
   idempotency require contract and sandbox tests before taking money.
4. PostgreSQL, Redis, and RabbitMQ are single local instances. Managed failover, network
   partition, multi-zone behavior, HTTPS, and deployed smoke tests belong to Day 12.
5. The suite prioritizes invariants and risky transitions; it does not claim 100% line
   coverage.

## Reproduce

Prerequisites are Java 21, Maven, Docker Desktop, and k6 on `PATH`.

```powershell
mvn verify
.\load-tests\run-day11.ps1 -Duration 30m -VirtualUsers 10
```

Both commands fail loudly on hidden errors: test failures, HTTP faults, reconciliation drift,
stranded holds, missing telemetry, closing connection pressure, or a sustained heap-growth
signal.
