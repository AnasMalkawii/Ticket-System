# Day 8 - Failure handling and recovery

## Reliability contract

Dependencies must fail within a known deadline. A timeout never weakens the inventory
transaction, and the server never retries a purchase action whose remote side effect is
ambiguous.

PostgreSQL remains the only dependency required for synchronous booking correctness.
Readiness therefore follows PostgreSQL. Redis and RabbitMQ are deliberately excluded from
readiness: Redis fast paths fail open/fall back, while RabbitMQ outages accumulate durable
outbox rows for later delivery.

## Configured deadlines

| Boundary | Limit | Failure behavior |
|---|---:|---|
| Hikari pool acquisition | 3 s | `503 DEPENDENCY_UNAVAILABLE`, transaction not started |
| PostgreSQL connect | 2 s | Connection attempt fails instead of hanging |
| PostgreSQL socket read | 5 s | Broken/unresponsive connection is discarded |
| PostgreSQL statement | 3 s | Statement is cancelled and transaction rolls back |
| PostgreSQL row lock | 2 s | Lock wait is cancelled and transaction rolls back |
| Spring transaction | 5 s | Outer application-side transaction deadline |
| RabbitMQ connect | 2 s | Outbox row remains unpublished |
| RabbitMQ publisher confirm | 3 s | Failure is persisted with bounded backoff |
| Redis connect / command | 200 ms / 300 ms | Catalog falls back; rate limiting fails open |
| Outbound HTTP connect / request | 500 ms / 1 s | Call fails; no implicit retry |
| Payment authorization | 1 s | `504 DEPENDENCY_TIMEOUT`; hold remains `PENDING` |
| Incoming HTTP connection / keep-alive | 2 s / 30 s | Slow or idle clients cannot hold connections indefinitely |

The PostgreSQL `statement_timeout` and `lock_timeout` are applied by Hikari to every new
session, not only by comments or client-side timers. `ReliabilityConfigurationIT` verifies
the live values using `SHOW statement_timeout` and `SHOW lock_timeout`.

## Retry policy

| Operation | Retried? | Reason |
|---|---|---|
| Reservation or confirmation request | No server retry | A client retry must use its idempotency contract; the server does not guess after an ambiguous response |
| Payment authorization | No | A purchase side effect is unsafe to repeat without a gateway idempotency key |
| Outbox publication | Yes, bounded exponential backoff | The event has a stable ID and durable source row; consumers deduplicate that ID |
| RabbitMQ consumer delivery | Yes, 4 bounded attempts, then DLQ | The consumer records event IDs atomically, so redelivery is safe |
| Redis cache/rate-limit call | No | The operation falls back/fails open immediately; retrying would lengthen the booking request |
| Outbound HTTP helper | No implicit retry | A feature adapter must opt in only for an explicitly idempotent operation |

## Health and shutdown

- `GET /actuator/health/liveness` and `GET /livez` answer whether the process can run.
- `GET /actuator/health/readiness` and `GET /readyz` include `readinessState` and `db`.
- A PostgreSQL outage produces readiness `DOWN`/HTTP 503 while liveness remains `UP`.
- When PostgreSQL recovers, Hikari recreates connections and readiness returns to `UP`
  automatically.
- `server.shutdown=graceful` stops new traffic and lets in-flight HTTP requests finish.
- Scheduled work awaits termination for up to 10 seconds. Rabbit consumers use
  `force-stop=false`, so an already-delivered message is allowed to finish.
- The complete Spring shutdown phase is capped at 20 seconds.

## Failure-drill record

| Drill | Injection | Observed result | Evidence |
|---|---|---|---|
| Abrupt app/connection loss | Abort a PostgreSQL connection with an uncommitted inventory write while a second request waits | Uncommitted movement rolled back, lock released, waiting request committed, reconciliation drift stayed zero | `FailureRecoveryIT.appCrashRollsBackAndWaitingReplicaRecovers` |
| RabbitMQ outage | Stop the Rabbit application during confirmation, then restore it | Booking/order committed; outbox remained durable; event drained after recovery and was processed once logically | `MessagingRecoveryIT.brokerOutageDuringConfirmationRecoversWithoutCorruptingBooking` |
| Redis outage | Point cache/rate limit at an unavailable Redis endpoint | Catalog fell back to PostgreSQL and booking committed with zero reconciliation drift | `RedisUnavailableIT.redisFailureDoesNotBecomeBookingFailure` |
| Slow database | Hold the hot inventory row beyond a 250 ms drill timeout | Request returned retryable 503 within 2 s, no partial state remained, retry with the same key succeeded | `FailureRecoveryIT.slowDatabaseFailsFastWithoutPartialInventoryMovement` |
| Duplicate delivery | Publish the same event ID twice | Queue received both deliveries; one `processed_event` row and one logical side effect resulted | `MessagingRecoveryIT.duplicateDeliveryIsIdempotent` |
| Database outage / probes | Pause and unpause a real PostgreSQL container | Readiness changed `UP -> DOWN -> UP`; liveness stayed `UP` | `HealthProbeRecoveryIT.probesDistinguishTrafficSafetyFromProcessLife` |
| Slow payment | `tok_timeout` exceeds the configured payment deadline | 504 returned before the simulated provider completed; reservation stayed `PENDING`, with no order or confirmation event | `ReserveApiIT.timedOutPaymentDoesNotChangeBookingState` |
| Slow generic HTTP dependency | Local server delays beyond the request deadline | One attempt timed out; request counter proved no blind retry | `BoundedHttpClientTest.requestDeadlineDoesNotBlindRetry` |

Run the complete evidence set with:

```powershell
mvn verify
```

Expected warnings and stack traces during this suite are the deliberately injected broker,
Redis, database, and poison-message failures. The Maven result and the reconciliation
assertions determine pass/fail.

Final Day 8 verification: **119 tests passed, 0 failures, 0 errors, 0 skipped**.
