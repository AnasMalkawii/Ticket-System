# Day 10 - Metrics, dashboards, logs, and alerts

## Outcome

The local reference deployment now explains a failed load test without an IDE. Prometheus
scrapes both application replicas and RabbitMQ, Grafana provisions one production overview,
five alert rules cover the primary failure signals, and application plus Nginx logs carry the
same request correlation id.

## Start the stack

Prerequisites: Docker Desktop and k6 on `PATH`.

```powershell
docker compose -f deploy/day10/docker-compose.yml up --build -d
```

| Surface | Local URL | Purpose |
|---|---|---|
| Ticket API | <http://127.0.0.1:28100> | Nginx entry point for both replicas |
| Grafana | <http://127.0.0.1:23000/d/ticket-system-day10/ticket-system-production-overview> | Provisioned operational dashboard |
| Prometheus | <http://127.0.0.1:29090> | Targets, queries, and evaluated alert rules |
| RabbitMQ UI | <http://127.0.0.1:25672> | Local broker inspection (`ticketing` / `ticketing`) |

Grafana is anonymous and bound to loopback for this local lab. The RabbitMQ credentials and
JWT key in this Compose file are also local-only. Replace all of them with managed secrets and
real authentication outside this workstation deployment.

The Nginx edge returns `404` for `/actuator/*`. Prometheus reaches
`/actuator/prometheus` directly over the private Compose network, while
`/actuator/metrics` remains restricted to administrators.

## Dashboard coverage

`Ticket System - Production Overview` contains 12 panels:

1. request traffic;
2. fleet-wide HTTP 5xx percentage;
3. HTTP p95 and p99;
4. Hikari active, pending, and maximum connections per replica;
5. JVM heap and process CPU per replica;
6. reservation attempts, successes, sold-out outcomes, and idempotency hits;
7. reservation transaction p95 and p99;
8. confirmation successes and failures by bounded domain reason;
9. RabbitMQ ready and unacknowledged messages by queue;
10. replica scrape health;
11. expired holds and failed expiry batches;
12. expiry p95 and confirmation p99.

The dashboard variable can select one replica or aggregate the two-replica fleet.

## Business metric contract

Metric labels are deliberately low-cardinality. User, reservation, event, and request IDs
belong in logs, never metric labels.

| Prometheus series | Meaning |
|---|---|
| `ticketing_reservation_attempts_total` | Every call entering the reservation service |
| `ticketing_reservation_success_total` | Successful responses, including safe idempotent replays |
| `ticketing_reservation_sold_out_total` | Correct sold-out business rejections |
| `ticketing_reservation_idempotency_hits_total` | Successes served by an existing key |
| `ticketing_reservation_failures_total{reason}` | Non-sold-out failures by stable domain code |
| `ticketing_confirmation_success_total` | Confirmation transactions committed |
| `ticketing_confirmation_failures_total{reason}` | Confirmation failures by stable domain code |
| `ticketing_reservation_expired_holds_total` | Reservation rows committed as expired |
| `ticketing_expiry_failures_total` | Expiry batches that rolled back |
| `ticketing_reservation_duration_seconds` | Reservation latency histogram, including commit |
| `ticketing_confirmation_duration_seconds` | Confirmation latency, including bounded payment |
| `ticketing_expiry_batch_duration_seconds` | Expiry batch latency, including commit |

Spring Boot also exports HTTP, JVM, process, and Hikari meters. RabbitMQ's Prometheus plugin
is scraped through its per-object endpoint so the `queue` label remains available.

## Alert rules

| Rule | Condition | Delay |
|---|---|---:|
| `TicketSystemErrorRateSpike` | Fleet 5xx ratio above 5% | 5 min |
| `TicketSystemHighReservationP95` | Reservation p95 above 300 ms | 5 min |
| `TicketSystemDatabasePoolSaturated` | Active Hikari connections above 90% of maximum | 2 min |
| `TicketSystemRabbitQueueBacklog` | More than 100 ready ticketing messages | 5 min |
| `TicketSystemHealthCheckFailed` | A replica cannot be scraped | 1 min |

Prometheus evaluates these rules locally. A production deployment should route them through
Alertmanager to the team's notification channels.

## Reproduce the diagnostic drill

With the stack running, execute a bounded hot-row load test:

```powershell
$env:BASE_URL = 'http://127.0.0.1:28100'
$env:VUS = '200'
$env:ARRIVAL_SPREAD_SECONDS = '1'
$env:RUN_ID = 'day10-smoke'
$env:SUITE_NAME = 'day10'
$keyBytes = [byte[]]::new(48)
[Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
$env:JWT_ACCESS_SECRET = [Convert]::ToBase64String($keyBytes)
[Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
$env:JWT_REFRESH_SECRET = [Convert]::ToBase64String($keyBytes)
k6 run load-tests/k6/day9-hot-row.js
```

The Day 10 verification run produced 200 attempts: 100 successful reservations, 100
`SOLD_OUT` responses, zero server faults, and zero unexpected responses. Prometheus reported
the same `200 / 100 / 100` business totals. Both application targets, RabbitMQ, and Prometheus
were healthy; Grafana loaded all 12 panels; Prometheus validated all five rules.

The drill also left 100 messages on `ticketing.analytics-audit`, immediately visible on the
queue-depth panel. That is an intentional unused audit consumer in the local topology and a
useful demonstration of backlog diagnosis.

Final application verification: **126 tests passed, 0 failures, 0 errors, 0 skipped**.

## Diagnose a failed load test

1. Start at traffic, 5xx rate, and HTTP p95/p99 to establish when impact began.
2. Compare both replicas. One unhealthy replica points to a local failure; both replicas with
   a full Hikari pool point to shared database pressure.
3. Compare reservation attempts with success, sold-out, and other failures. `SOLD_OUT` is a
   correct inventory outcome, not a server error.
4. Inspect per-queue ready and unacknowledged counts. A growing queue with healthy HTTP traffic
   isolates an asynchronous consumer or broker problem.
5. Copy an `X-Request-Id` from the failing response and search the JSON logs:

```powershell
docker compose -f deploy/day10/docker-compose.yml logs --no-color app1 app2 nginx |
  Select-String '<request-id>'
```

Application failure-completion records include `requestId`, `http.method`, `http.path`,
`http.status`, `durationMs`, `service.name`, and `service.instance`. Outbox publisher and
RabbitMQ consumer logs restore the original message correlation id, so the same search follows
the request into asynchronous processing.

Stop the lab without deleting its Prometheus or Grafana volumes:

```powershell
docker compose -f deploy/day10/docker-compose.yml down
```
