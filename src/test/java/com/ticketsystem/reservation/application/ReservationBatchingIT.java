package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.schema.AbstractPostgresIT;
import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "ticketing.reservation-batching.enabled=true",
        "ticketing.reservation-batching.window=10ms",
        "ticketing.reservation-batching.max-batch-size=1000"
})
class ReservationBatchingIT extends AbstractPostgresIT {

    private static final UUID HOT = UUID.fromString(HOT_EVENT);

    @Autowired
    private ReservationRequestService reservations;

    @Test
    @DisplayName("a 300-request batch sells exactly 100 tickets and writes 100 outbox rows")
    void batchNeverOversellsAndKeepsTheOutboxAtomic() throws Exception {
        BatchOutcome outcome = run(300, index -> command(
                UUID.randomUUID(), 1, "batch-oversell-" + UUID.randomUUID()));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.accepted()).isEqualTo(100);
        assertThat(outcome.rejected().getOrDefault(ErrorCode.SOLD_OUT, 0)).isEqualTo(200);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'reservation.created'",
                Integer.class)).isEqualTo(100);
        assertInventoryAndLedger(0, 100, 100);
    }

    @Test
    @DisplayName("requests in one batch cannot race past the per-user event cap")
    void batchPreservesThePerUserCap() throws Exception {
        UUID user = UUID.randomUUID();
        BatchOutcome outcome = run(25, index -> command(
                user, 1, "batch-cap-" + UUID.randomUUID()));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.accepted()).isEqualTo(4);
        assertThat(outcome.rejected().getOrDefault(ErrorCode.USER_LIMIT_EXCEEDED, 0))
                .isEqualTo(21);
        assertInventoryAndLedger(96, 4, 4);
    }

    @Test
    @DisplayName("simultaneous duplicate keys in a batch return one logical reservation")
    void batchPreservesIdempotency() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "batch-retry-" + UUID.randomUUID();
        BatchOutcome outcome = run(10, index -> command(user, 2, key));

        assertThat(outcome.unexpected()).isEmpty();
        assertThat(outcome.accepted()).isEqualTo(10);
        assertThat(outcome.reservationIds()).hasSize(10).containsOnly(
                outcome.reservationIds().getFirst());
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM reservation WHERE idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE type = 'reservation.created'",
                Integer.class)).isEqualTo(1);
        assertInventoryAndLedger(98, 2, 1);
    }

    private ReserveTicketsCommand command(UUID userId, int quantity, String key) {
        return new ReserveTicketsCommand(HOT, userId, quantity, key,
                RequestFingerprint.of(HOT, userId, quantity), null);
    }

    private BatchOutcome run(int attempts,
                             IntFunction<ReserveTicketsCommand> commandFactory) throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        Map<ErrorCode, Integer> rejected = new ConcurrentHashMap<>();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        List<UUID> ids = new CopyOnWriteArrayList<>();
        CyclicBarrier start = new CyclicBarrier(attempts);
        CountDownLatch finished = new CountDownLatch(attempts);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < attempts; index++) {
                int request = index;
                pool.submit(() -> {
                    try {
                        ReserveTicketsCommand command = commandFactory.apply(request);
                        start.await(30, TimeUnit.SECONDS);
                        Reservation reservation = reservations.reserveWithResult(command).reservation();
                        accepted.incrementAndGet();
                        ids.add(reservation.getId());
                    } catch (DomainException domainFailure) {
                        rejected.merge(domainFailure.errorCode(), 1, Integer::sum);
                    } catch (Throwable failure) {
                        unexpected.add(failure);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }
        return new BatchOutcome(accepted.get(), Map.copyOf(rejected),
                List.copyOf(unexpected), List.copyOf(ids));
    }

    private void assertInventoryAndLedger(int available, int held, int reservationRows) {
        Map<String, Object> inventory = jdbc().queryForMap(
                "SELECT available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);
        assertThat(inventory.get("available")).isEqualTo(available);
        assertThat(inventory.get("held")).isEqualTo(held);
        assertThat(inventory.get("sold")).isEqualTo(0);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM reservation WHERE event_id = ?::uuid",
                Integer.class, HOT_EVENT)).isEqualTo(reservationRows);
        assertThat(jdbc().queryForList("""
                SELECT event_id FROM v_inventory_reconciliation
                WHERE conservation_drift <> 0 OR held_drift <> 0 OR sold_drift <> 0
                """)).isEmpty();
    }

    private record BatchOutcome(int accepted,
                                Map<ErrorCode, Integer> rejected,
                                List<Throwable> unexpected,
                                List<UUID> reservationIds) {
    }
}
