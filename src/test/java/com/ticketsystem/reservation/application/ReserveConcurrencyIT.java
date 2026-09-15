package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.reservation.domain.IdempotencyInProgressException;
import com.ticketsystem.schema.AbstractPostgresIT;
import com.ticketsystem.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Day 3 acceptance gate: 100 tickets, hundreds of competing reserve attempts, and the
 * accepted quantity must never exceed 100 with counters reconciling exactly.
 *
 * <p>Runs against real PostgreSQL. An in-memory database would prove nothing here - the
 * behaviour under test is {@code SELECT ... FOR UPDATE} row locking, which is precisely what
 * H2 does not reproduce faithfully.
 */
class ReserveConcurrencyIT extends AbstractPostgresIT {

    private static final UUID HOT = UUID.fromString(HOT_EVENT);

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetInventoryAndLedger() {
        jdbc().update("DELETE FROM outbox_event");
        jdbc().update("DELETE FROM ticket_order");
        jdbc().update("DELETE FROM reservation");
        jdbc().update("UPDATE ticket_inventory SET total = 100, available = 100, held = 0, sold = 0 "
                + "WHERE event_id = ?::uuid", HOT_EVENT);
    }

    private ReserveTicketsCommand command(UUID userId, int quantity) {
        String key = "k-" + UUID.randomUUID();
        return new ReserveTicketsCommand(HOT, userId, quantity, key,
                RequestFingerprint.of(HOT, userId, quantity), null);
    }

    @Test
    @DisplayName("300 simultaneous single-ticket attempts on 100 tickets: exactly 100 succeed")
    void hundredTicketsCannotBecomeOneHundredAndOne() throws Exception {
        int attempts = 300;

        var outcome = ConcurrentReserveHarness.run(attempts,
                index -> command(UUID.randomUUID(), 1), reserveTicketsService);

        assertThat(outcome.unexpectedFailures())
                .as("no request may fail for a reason other than a domain rejection")
                .isEmpty();
        assertThat(outcome.accepted()).as("exactly the inventory, no more and no fewer").isEqualTo(100);
        assertThat(outcome.rejected(ErrorCode.SOLD_OUT)).isEqualTo(attempts - 100);
        assertThat(outcome.totalRejected()).isEqualTo(attempts - 100);

        assertInventory(0, 100, 0);
        assertReconciles();

        System.out.printf("[reserve-race] %d attempts, %d accepted, %d SOLD_OUT, wall clock %d ms%n",
                attempts, outcome.accepted(), outcome.rejected(ErrorCode.SOLD_OUT),
                outcome.wallClock().toMillis());
    }

    @Test
    @DisplayName("mixed quantities still cannot exceed the inventory")
    void mixedQuantitiesNeverOversell() throws Exception {
        int attempts = 200;

        var outcome = ConcurrentReserveHarness.run(attempts,
                // Quantities 1..4, so the total demanded (about 500) far exceeds the 100
                // available and the last accepted hold rarely lands exactly on zero.
                index -> command(UUID.randomUUID(), 1 + (index % 4)), reserveTicketsService);

        assertThat(outcome.unexpectedFailures()).isEmpty();
        assertThat(outcome.acceptedQuantity())
                .as("accepted tickets may not exceed inventory")
                .isLessThanOrEqualTo(100);

        Map<String, Object> inventory = inventoryRow();
        assertThat((Integer) inventory.get("held")).isEqualTo(outcome.acceptedQuantity());
        assertThat((Integer) inventory.get("available")).isEqualTo(100 - outcome.acceptedQuantity());
        assertReconciles();

        System.out.printf("[mixed-qty] %d attempts, %d holds, %d tickets held, wall clock %d ms%n",
                attempts, outcome.accepted(), outcome.acceptedQuantity(),
                outcome.wallClock().toMillis());
    }

    @Test
    @DisplayName("one user racing themselves cannot bypass the per-event cap (I6)")
    void perUserCapSurvivesParallelRequests() throws Exception {
        UUID user = UUID.randomUUID();
        int attempts = 25;

        var outcome = ConcurrentReserveHarness.run(attempts,
                index -> command(user, 1), reserveTicketsService);

        assertThat(outcome.unexpectedFailures()).isEmpty();
        assertThat(outcome.accepted())
                .as("the cap is 4 tickets per user per event, however parallel the attempts")
                .isEqualTo(4);
        assertThat(outcome.rejected(ErrorCode.USER_LIMIT_EXCEEDED)).isEqualTo(attempts - 4);

        Integer ledger = jdbc().queryForObject(
                "SELECT COALESCE(SUM(qty), 0) FROM reservation WHERE user_id = ?::uuid "
                        + "AND status IN ('PENDING', 'CONFIRMED')", Integer.class, user);
        assertThat(ledger).isEqualTo(4);
        assertInventory(96, 4, 0);
        assertReconciles();
    }

    @Test
    @DisplayName("every accepted hold writes exactly one outbox event in the same transaction")
    void acceptedHoldsProduceOutboxEvents() throws Exception {
        var outcome = ConcurrentReserveHarness.run(150,
                index -> command(UUID.randomUUID(), 1), reserveTicketsService);

        Integer outboxRows = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'reservation.created'", Integer.class);

        assertThat(outboxRows)
                .as("rejected attempts roll back their outbox row along with everything else")
                .isEqualTo(outcome.accepted());
        assertThat(outboxRows).isEqualTo(100);
    }

    @Test
    @DisplayName("10 simultaneous retries with one Idempotency-Key return one logical hold (I3)")
    void simultaneousIdenticalRetriesReturnOneReservation() throws Exception {
        int attempts = 10;
        UUID user = UUID.randomUUID();
        String key = "retry-" + UUID.randomUUID();
        int quantity = 2;
        ReserveTicketsCommand duplicateCommand = new ReserveTicketsCommand(
                HOT, user, quantity, key, RequestFingerprint.of(HOT, user, quantity), null);

        CyclicBarrier startLine = new CyclicBarrier(attempts);
        ConcurrentLinkedQueue<UUID> reservationIds = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await(30, TimeUnit.SECONDS);
                        reservationIds.add(reserveTicketsService.reserve(duplicateCommand).getId());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
        }

        assertThat(failures)
                .as("every identical retry should succeed as either the winner or a replay")
                .isEmpty();
        assertThat(reservationIds).hasSize(attempts);

        UUID storedId = jdbc().queryForObject(
                "SELECT id FROM reservation WHERE idempotency_key = ?", UUID.class, key);
        assertThat(reservationIds)
                .as("all retries must return the reservation created by the single winning transaction")
                .containsOnly(storedId);

        Integer reservationRows = jdbc().queryForObject(
                "SELECT count(*) FROM reservation WHERE idempotency_key = ?", Integer.class, key);
        Integer outboxRows = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid "
                        + "AND type = 'reservation.created'",
                Integer.class, storedId);

        assertThat(reservationRows).isEqualTo(1);
        assertThat(outboxRows).isEqualTo(1);
        assertInventory(100 - quantity, quantity, 0);
        assertReconciles();
    }

    @Test
    @DisplayName("a duplicate stops waiting when the winning request remains in flight")
    void slowWinnerReturnsIdempotencyInProgress() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "slow-winner-" + UUID.randomUUID();
        ReserveTicketsCommand duplicateCommand = new ReserveTicketsCommand(
                HOT, user, 1, key, RequestFingerprint.of(HOT, user, 1), null);

        CountDownLatch inventoryLocked = new CountDownLatch(1);
        CountDownLatch releaseInventory = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> lockHolder = pool.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.executeWithoutResult(status -> {
                    jdbc().queryForObject(
                            "SELECT event_id FROM ticket_inventory "
                                    + "WHERE event_id = ?::uuid FOR UPDATE",
                            UUID.class, HOT_EVENT);
                    inventoryLocked.countDown();
                    try {
                        if (!releaseInventory.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out holding the inventory lock");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                });
            });

            Future<com.ticketsystem.reservation.domain.Reservation> winner;
            try {
                assertThat(inventoryLocked.await(10, TimeUnit.SECONDS)).isTrue();
                winner = pool.submit(() -> reserveTicketsService.reserve(duplicateCommand));

                awaitTransactionWaitingOnInventory();

                Instant started = Instant.now();
                assertThatThrownBy(() -> reserveTicketsService.reserveWithResult(duplicateCommand))
                        .isInstanceOf(IdempotencyInProgressException.class)
                        .extracting(failure ->
                                ((IdempotencyInProgressException) failure).getRetryAfterSeconds())
                        .isEqualTo(1);
                assertThat(Duration.between(started, Instant.now()))
                        .as("the duplicate wait must be bounded")
                        .isLessThan(Duration.ofSeconds(10));
            } finally {
                releaseInventory.countDown();
            }

            var created = winner.get(30, TimeUnit.SECONDS);
            lockHolder.get(30, TimeUnit.SECONDS);

            ReserveTicketsResult replay = reserveTicketsService.reserveWithResult(duplicateCommand);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.reservation().getId()).isEqualTo(created.getId());
        }

        assertInventory(99, 1, 0);
        assertReconciles();
    }

    private void awaitTransactionWaitingOnInventory() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc().queryForObject("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query ILIKE '%ticket_inventory%'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("The winning reserve transaction never reached the inventory lock");
    }

    private Map<String, Object> inventoryRow() {
        return jdbc().queryForMap(
                "SELECT total, available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);
    }

    private void assertInventory(int available, int held, int sold) {
        Map<String, Object> row = inventoryRow();
        assertThat(row.get("available")).isEqualTo(available);
        assertThat(row.get("held")).isEqualTo(held);
        assertThat(row.get("sold")).isEqualTo(sold);
    }

    /** The C1 release-gate query from docs/slo.md. Must return zero rows. */
    private void assertReconciles() {
        var drift = jdbc().queryForList("""
                SELECT event_id, conservation_drift, held_drift, sold_drift
                FROM v_inventory_reconciliation
                WHERE conservation_drift <> 0 OR held_drift <> 0 OR sold_drift <> 0
                """);
        assertThat(drift).as("inventory counters must agree with the reservation ledger").isEmpty();
    }
}
