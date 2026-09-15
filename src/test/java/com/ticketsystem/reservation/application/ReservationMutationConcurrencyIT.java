package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.order.application.ConfirmReservationService;
import com.ticketsystem.reservation.domain.IllegalReservationTransitionException;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationStatus;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Day 11 release-gate races for lifecycle mutations delivered more than once. */
class ReservationMutationConcurrencyIT extends AbstractPostgresIT {

    private static final UUID HOT = UUID.fromString(HOT_EVENT);
    private static final int ATTEMPTS = 20;

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Autowired
    private CancelReservationService cancelReservationService;

    @Autowired
    private ConfirmReservationService confirmReservationService;

    @Test
    @DisplayName("20 simultaneous cancellations release one hold exactly once")
    void simultaneousCancellationsHaveOneWinner() throws Exception {
        Reservation reservation = reserve(3);
        ConcurrentOutcome outcome = race(attempt -> cancelReservationService.cancel(
                reservation.getId(), reservation.getUserId(), "cancel-race-" + attempt));

        assertThat(outcome.successes()).isOne();
        assertThat(outcome.expectedLosers()).isEqualTo(ATTEMPTS - 1);
        assertThat(outcome.unexpectedFailures()).isEmpty();
        assertState(reservation.getId(), ReservationStatus.CANCELLED, 100, 0, 0);
        assertOutboxCount(reservation.getId(), "reservation.cancelled", 1);
    }

    @Test
    @DisplayName("20 simultaneous confirmations create one order and sell one hold exactly once")
    void simultaneousConfirmationsHaveOneWinner() throws Exception {
        Reservation reservation = reserve(2);
        ConcurrentOutcome outcome = race(attempt -> confirmReservationService.confirm(
                reservation.getId(), reservation.getUserId(), "tok_ok", "confirm-race-" + attempt));

        assertThat(outcome.successes()).isOne();
        assertThat(outcome.expectedLosers()).isEqualTo(ATTEMPTS - 1);
        assertThat(outcome.unexpectedFailures()).isEmpty();
        assertState(reservation.getId(), ReservationStatus.CONFIRMED, 98, 0, 2);

        Integer orders = jdbc().queryForObject(
                "SELECT count(*) FROM ticket_order WHERE reservation_id = ?::uuid",
                Integer.class, reservation.getId());
        assertThat(orders).as("duplicate confirmation must not create another charge/order").isOne();
        assertOutboxCount(reservation.getId(), "reservation.confirmed", 1);
    }

    private ConcurrentOutcome race(ConcurrentMutation mutation) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(ATTEMPTS);
        ConcurrentLinkedQueue<Integer> winners = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> expectedLosers = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> unexpectedFailures = new ConcurrentLinkedQueue<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                int attemptNumber = attempt;
                pool.submit(() -> {
                    try {
                        startLine.await(30, TimeUnit.SECONDS);
                        mutation.run(attemptNumber);
                        winners.add(attemptNumber);
                    } catch (IllegalReservationTransitionException lostRace) {
                        expectedLosers.add(lostRace);
                    } catch (Throwable failure) {
                        unexpectedFailures.add(failure);
                    }
                });
            }
        }

        return new ConcurrentOutcome(
                winners.size(), expectedLosers.size(), unexpectedFailures);
    }

    private Reservation reserve(int quantity) {
        UUID user = UUID.randomUUID();
        String key = "mutation-race-" + UUID.randomUUID();
        return reserveTicketsService.reserve(new ReserveTicketsCommand(
                HOT, user, quantity, key, RequestFingerprint.of(HOT, user, quantity),
                "day11-mutation-race"));
    }

    private void assertState(UUID reservationId, ReservationStatus status,
                             int available, int held, int sold) {
        Map<String, Object> reservation = jdbc().queryForMap(
                "SELECT status, terminated_at IS NOT NULL AS terminated "
                        + "FROM reservation WHERE id = ?::uuid",
                reservationId);
        assertThat(reservation)
                .containsEntry("status", status.name())
                .containsEntry("terminated", true);

        Map<String, Object> inventory = jdbc().queryForMap(
                "SELECT available, held, sold FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);
        assertThat(inventory)
                .containsEntry("available", available)
                .containsEntry("held", held)
                .containsEntry("sold", sold);

        Map<String, Object> reconciliation = jdbc().queryForMap("""
                SELECT conservation_drift, held_drift, sold_drift
                FROM v_inventory_reconciliation
                WHERE event_id = ?::uuid
                """, HOT_EVENT);
        assertThat(((Number) reconciliation.get("conservation_drift")).longValue()).isZero();
        assertThat(((Number) reconciliation.get("held_drift")).longValue()).isZero();
        assertThat(((Number) reconciliation.get("sold_drift")).longValue()).isZero();
    }

    private void assertOutboxCount(UUID reservationId, String type, int expected) {
        Integer events = jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?::uuid AND type = ?",
                Integer.class, reservationId, type);
        assertThat(events).isEqualTo(expected);
    }

    @FunctionalInterface
    private interface ConcurrentMutation {
        void run(int attempt);
    }

    private record ConcurrentOutcome(
            int successes,
            int expectedLosers,
            ConcurrentLinkedQueue<Throwable> unexpectedFailures) {
    }
}
