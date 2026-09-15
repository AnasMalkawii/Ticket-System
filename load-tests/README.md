# Day 6 load-test harness

This harness runs the four Day 6 scenarios against an isolated PostgreSQL 16 and Redis 7
environment:

- `baseline.js`: 500 concurrent one-ticket attempts against exactly 100 tickets.
- `duplicate-retries.js`: 25 simultaneous requests with one idempotency key.
- `mixed-reserve-cancel.js`: 100 reservations with 50 owner cancellations.
- `sold-out-spike.js`: 500 attempts against an event that is already sold out.

Run from PowerShell at the repository root:

```powershell
.\load-tests\run-day6.ps1
```

Prerequisites are Java 21, Maven, Docker Desktop, and k6. The runner builds the application,
starts isolated dependencies on ports 15432/16379, starts the application on port 18080,
and cleans everything up afterward. It creates a timestamped directory under
`load-tests/results/` containing:

- k6 JSON summaries, console logs, and HTML dashboards;
- Hikari and PostgreSQL contention samples;
- authoritative reconciliation results for every scenario;
- machine/runtime metadata.

The `load` Spring profile contains local-only credentials and disables Redis rate limiting
and automatic expiry so the test measures the PostgreSQL reservation path itself. Never use
that profile for a deployed environment.

## Day 9 two-replica benchmark

The Day 9 harness builds one application image, starts two stateless replicas behind Nginx,
and connects both to one PostgreSQL primary. It compares the original pessimistic lock with
the conditional atomic inventory update at 500, 1,000, and 2,000 virtual users:

```powershell
.\load-tests\run-day9.ps1
```

The runner proves that a token issued by one replica works on the other, observes both
replicas through Nginx, captures pool and database contention, and fails if reconciliation
finds an oversell or inventory drift. Results are written under `load-tests/results/day9-*`.

## Day 10 observability drill

The Day 10 deployment adds Prometheus, a provisioned 12-panel Grafana dashboard, RabbitMQ
per-queue metrics, five alert rules, and JSON correlation logs around the same two replicas.
Start it and run the bounded 200-user diagnostic drill described in
[`docs/day10-observability.md`](../docs/day10-observability.md).

## Day 11 release-candidate soak

The Day 11 harness keeps reserve, idempotent replay, and cancellation traffic running against
both replicas while sampling JVM heap, Hikari usage, and PostgreSQL activity. It fails on any
HTTP/server fault, inventory drift, stranded hold, pending connection after cooldown, or
sustained heap-growth signal. The roadmap-duration run is:

```powershell
.\load-tests\run-day11.ps1 -Duration 30m -VirtualUsers 10
```

Use a shorter duration only as a local smoke check and record that limitation in the release
report. Raw k6 output, time-series telemetry, reconciliation, and the computed release metrics
are stored under `load-tests/results/day11-*`.

## Live failure-injection drill

The resilience drill keeps idempotent booking traffic active while one application replica
is killed and Redis, RabbitMQ, and PostgreSQL are interrupted in turn. Every logical booking
retries with the same idempotency key so a response lost around a commit can be resolved
without creating a second hold:

```powershell
.\load-tests\run-resilience-drill.ps1 -Duration 8m -VirtualUsers 30
```

It records both raw HTTP-attempt availability (which is expected to fall during the
deliberate single-database outage) and eventual logical-operation availability. Final gates
cover oversell/drift, exact client/database accounting, unexpected business rejections,
readiness recovery, connection pressure, and outbox recovery.

## Exact 500-VU / 10,000-request burst

The high-traffic scenario sends exactly 10,000 unique reservation attempts from 500 virtual
users against one 10,000-ticket hot event. Run k6 inside the Compose network to measure the
application and gateway without the Windows published-port bridge becoming the bottleneck:

```powershell
.\load-tests\run-high-traffic.ps1 -K6InDocker
```

The runner starts the Day 10 two-replica topology, captures one-second Hikari/PostgreSQL
telemetry, reconciles the final inventory, records gateway and pool-timeout diagnostics, and
cleans up the isolated stack. The measured 2026-09-06 result and assessment are in
[`docs/high-traffic-500vu-10000-report.md`](../docs/high-traffic-500vu-10000-report.md).
