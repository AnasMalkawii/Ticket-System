# Day 7 - Transactional outbox and RabbitMQ

## Reliability contract

Reservation confirmation commits four database changes atomically: the reservation becomes
`CONFIRMED`, held inventory becomes sold inventory, one `ticket_order` is created, and one
`reservation.confirmed` outbox row is inserted. RabbitMQ is never called by that transaction.

The publisher later claims ready rows with `FOR UPDATE SKIP LOCKED`, sends persistent messages,
waits for a correlated broker acknowledgement, and only then sets `published_at`. A crash after
the broker ack but before the database commit can redeliver an event, so consumers must dedupe by
the outbox event ID.

Publisher failures increment `attempts`, retain a truncated `last_error`, and schedule
`next_attempt_at` using exponential backoff from 250 ms up to 30 seconds. Events are never
discarded because an infrastructure outage can outlast any arbitrary attempt limit.

## RabbitMQ topology

| Exchange / queue | Binding | Purpose |
|---|---|---|
| `ticketing.events` | topic exchange | Durable event fan-out |
| `ticketing.reservation-confirmed` | `reservation.confirmed` | Reservation-confirmed work |
| `ticketing.notification-mock` | `reservation.confirmed` | Idempotent notification mock consumer |
| `ticketing.analytics-audit` | `reservation.#` | Reservation analytics and audit work |
| `ticketing.dead-letter` | direct exchange | Poison-message routing |
| `ticketing.poison.dlq` | `poison` | Messages rejected after bounded retries |

The notification consumer writes `(event_id, consumer)` to `processed_event` with PostgreSQL
`ON CONFLICT DO NOTHING`. This stays race-safe across replicas and makes duplicate delivery one
logical side effect. Consumer failures retry four times with 250 ms / 500 ms / 1 s bounded
backoff, then RabbitMQ dead-letters the message.

## Acceptance drill

`MessagingRecoveryIT` uses real PostgreSQL and RabbitMQ Testcontainers and proves:

1. Stop the RabbitMQ application.
2. Confirm a reservation successfully and reconcile inventory/order state.
3. Attempt publication and verify the outbox row remains unpublished with retry state.
4. Restart RabbitMQ and drain the outbox only after broker acknowledgement.
5. Verify the confirmation is processed once logically.
6. Deliver the same event twice and verify one `processed_event` row.
7. Send a poison message and verify it reaches `ticketing.poison.dlq` after bounded retries.

Run the focused drill with:

```powershell
mvn "-Dit.test=MessagingRecoveryIT" verify
```
