package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.reservation.domain.IllegalReservationTransitionException;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationStatus;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Day 4 proof for reservation termination.
 *
 * <p>Every assertion is made against PostgreSQL after the transaction commits. The state
 * transition alone is not enough: the matching inventory movement must happen once, and the
 * independent reconciliation view must agree with both records of the truth.
 */
class ReservationLifecycleIT extends AbstractPostgresIT {

    private static final UUID HOT = UUID.fromString(HOT_EVENT);
    private static final String FINGERPRINT = "e".repeat(64);

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Autowired
    private CancelReservationService cancelReservationService;

    @Autowired
    private ReservationExpiryWorker reservationExpiryWorker;

    @Test
    @DisplayName("cancellation returns a hold once and a repeated cancellation cannot release it again")
    void successfulAndRepeatedCancellation() {
        Reservation reservation = reserve(3);

        cancelReservationService.cancel(
                reservation.getId(), reservation.getUserId(), "cancel-first");

        assertReservationStatus(reservation.getId(), ReservationStatus.CANCELLED);
        assertInventory(100, 100, 0, 0);
        assertReconciles();

        assertThatThrownBy(() ->
                cancelReservationService.cancel(
                        reservation.getId(), reservation.getUserId(), "cancel-repeat"))
                .isInstanceOf(IllegalReservationTransitionException.class);

        assertReservationStatus(reservation.getId(), ReservationStatus.CANCELLED);
        assertInventory(100, 100, 0, 0);
        assertReconciles();
    }

    @Test
    @DisplayName("an expired hold is released once and later expiry batches are no-ops")
    void expiryIsRepeatSafe() {
        Reservation reservation = reserve(4);
        makeExpired(reservation.getId());

        assertThat(reservationExpiryWorker.expireBatch()).isEqualTo(1);

        assertReservationStatus(reservation.getId(), ReservationStatus.EXPIRED);
        assertInventory(100, 100, 0, 0);
        assertReconciles();

        assertThat(reservationExpiryWorker.expireBatch()).isZero();

        assertReservationStatus(reservation.getId(), ReservationStatus.EXPIRED);
        assertInventory(100, 100, 0, 0);
        assertReconciles();
    }

    @Test
    @DisplayName("two expiry workers split an expired backlog and release every hold exactly once")
    void twoExpiryWorkersCooperate() throws Exception {
        int expiredHolds = 250;
        seedExpiredHolds(expiredHolds);

        CyclicBarrier startLine = new CyclicBarrier(2);
        int first;
        int second;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> firstWorker = pool.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return reservationExpiryWorker.expireBatch();
            });
            Future<Integer> secondWorker = pool.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return reservationExpiryWorker.expireBatch();
            });

            first = firstWorker.get(2, TimeUnit.MINUTES);
            second = secondWorker.get(2, TimeUnit.MINUTES);
        }

        assertThat(first).isPositive();
        assertThat(second).isPositive();
        assertThat(first + second).isEqualTo(expiredHolds);

        Integer stillPending = jdbc().queryForObject(
                "SELECT count(*) FROM reservation WHERE status = 'PENDING'", Integer.class);
        Integer expired = jdbc().queryForObject(
                "SELECT count(*) FROM reservation WHERE status = 'EXPIRED'", Integer.class);
        assertThat(stillPending).isZero();
        assertThat(expired).isEqualTo(expiredHolds);
        assertInventory(expiredHolds, expiredHolds, 0, 0);
        assertReconciles();
    }

    @Test
    @DisplayName("cancellation racing expiry has one winner and returns inventory once")
    void cancellationRacingExpiry() throws Exception {
        Reservation reservation = reserve(2);
        makeExpired(reservation.getId());

        CyclicBarrier startLine = new CyclicBarrier(2);
        boolean cancellationWon;
        int expired;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> cancellation = pool.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                try {
                    cancelReservationService.cancel(
                            reservation.getId(), reservation.getUserId(), "cancel-race");
                    return true;
                } catch (IllegalReservationTransitionException lostRace) {
                    return false;
                }
            });
            Future<Integer> expiry = pool.submit(() -> {
                startLine.await(30, TimeUnit.SECONDS);
                return reservationExpiryWorker.expireBatch();
            });

            cancellationWon = cancellation.get(2, TimeUnit.MINUTES);
            expired = expiry.get(2, TimeUnit.MINUTES);
        }

        assertThat((cancellationWon ? 1 : 0) + expired)
                .as("exactly one transition must win")
                .isEqualTo(1);

        ReservationStatus finalStatus = reservationStatus(reservation.getId());
        if (cancellationWon) {
            assertThat(finalStatus).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(expired).isZero();
        } else {
            assertThat(finalStatus).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(expired).isEqualTo(1);
        }

        assertInventory(100, 100, 0, 0);
        assertReconciles();
    }

    private Reservation reserve(int quantity) {
        UUID userId = UUID.randomUUID();
        String key = "lifecycle-" + UUID.randomUUID();
        ReserveTicketsCommand command = new ReserveTicketsCommand(
                HOT, userId, quantity, key,
                RequestFingerprint.of(HOT, userId, quantity), "lifecycle-test");
        return reserveTicketsService.reserve(command);
    }

    private void makeExpired(UUID reservationId) {
        int updated = jdbc().update(
                "UPDATE reservation SET expires_at = now() - INTERVAL '1 second' "
                        + "WHERE id = ?::uuid",
                reservationId);
        assertThat(updated).isEqualTo(1);
    }

    private void seedExpiredHolds(int count) {
        jdbc().update(
                "UPDATE ticket_inventory "
                        + "SET total = ?, available = 0, held = ?, sold = 0, updated_at = now() "
                        + "WHERE event_id = ?::uuid",
                count, count, HOT_EVENT);
        jdbc().update("""
                INSERT INTO reservation (id, event_id, user_id, qty, status, idempotency_key,
                                         request_fingerprint, expires_at)
                SELECT gen_random_uuid(), ?::uuid, gen_random_uuid(), 1, 'PENDING',
                       'expiry-race-' || sequence_number, ?, now() - INTERVAL '1 second'
                FROM generate_series(1, ?) AS sequence_number
                """, HOT_EVENT, FINGERPRINT, count);
        assertReconciles();
    }

    private void assertReservationStatus(UUID reservationId, ReservationStatus expected) {
        assertThat(reservationStatus(reservationId)).isEqualTo(expected);
        Boolean hasTerminationTime = jdbc().queryForObject(
                "SELECT terminated_at IS NOT NULL FROM reservation WHERE id = ?::uuid",
                Boolean.class, reservationId);
        assertThat(hasTerminationTime).isTrue();
    }

    private ReservationStatus reservationStatus(UUID reservationId) {
        String status = jdbc().queryForObject(
                "SELECT status FROM reservation WHERE id = ?::uuid",
                String.class, reservationId);
        return ReservationStatus.valueOf(status);
    }

    private void assertInventory(int total, int available, int held, int sold) {
        Map<String, Object> inventory = jdbc().queryForMap(
                "SELECT total, available, held, sold "
                        + "FROM ticket_inventory WHERE event_id = ?::uuid",
                HOT_EVENT);
        assertThat(inventory.get("total")).isEqualTo(total);
        assertThat(inventory.get("available")).isEqualTo(available);
        assertThat(inventory.get("held")).isEqualTo(held);
        assertThat(inventory.get("sold")).isEqualTo(sold);
    }

    private void assertReconciles() {
        Map<String, Object> reconciliation = jdbc().queryForMap("""
                SELECT conservation_drift, held_drift, sold_drift
                FROM v_inventory_reconciliation
                WHERE event_id = ?::uuid
                """, HOT_EVENT);
        assertThat(((Number) reconciliation.get("conservation_drift")).longValue()).isZero();
        assertThat(((Number) reconciliation.get("held_drift")).longValue()).isZero();
        assertThat(((Number) reconciliation.get("sold_drift")).longValue()).isZero();
    }
}
