package com.ticketsystem.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketsystem.inventory.domain.InsufficientInventoryException;
import com.ticketsystem.order.application.ConfirmReservationService;
import com.ticketsystem.order.domain.PaymentDeclinedException;
import com.ticketsystem.reservation.application.RequestFingerprint;
import com.ticketsystem.reservation.application.ReservationExpiryWorker;
import com.ticketsystem.reservation.application.ReserveTicketsCommand;
import com.ticketsystem.reservation.application.ReserveTicketsResult;
import com.ticketsystem.reservation.application.ReserveTicketsService;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.schema.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proves business meters are wired to committed service outcomes, not merely registered. */
class ObservabilityMetricsIT extends AbstractPostgresIT {

    private static final UUID HOT = UUID.fromString(HOT_EVENT);

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ReserveTicketsService reservations;

    @Autowired
    private ConfirmReservationService confirmations;

    @Autowired
    private ReservationExpiryWorker expiryWorker;

    @Test
    @DisplayName("reservation attempts split into success, idempotency hit, and sold-out outcomes")
    void reservationOutcomeMetricsFollowTheRealTransaction() {
        double attemptsBefore = counter("ticketing.reservation.attempts");
        double successBefore = counter("ticketing.reservation.success");
        double idempotencyBefore = counter("ticketing.reservation.idempotency_hits");
        double soldOutBefore = counter("ticketing.reservation.sold_out");
        long latencyBefore = timerCount("ticketing.reservation.duration");

        UUID user = UUID.randomUUID();
        String key = "metrics-" + UUID.randomUUID();
        ReserveTicketsResult created = reservations.reserveWithResult(command(user, 1, key));
        ReserveTicketsResult replayed = reservations.reserveWithResult(command(user, 1, key));

        jdbc().update("UPDATE ticket_inventory SET available = 0, held = total "
                + "WHERE event_id = ?::uuid", HOT_EVENT);
        assertThatThrownBy(() -> reservations.reserve(
                command(UUID.randomUUID(), 1, "metrics-" + UUID.randomUUID())))
                .isInstanceOf(InsufficientInventoryException.class);

        assertThat(created.replayed()).isFalse();
        assertThat(replayed.replayed()).isTrue();
        assertThat(counter("ticketing.reservation.attempts") - attemptsBefore).isEqualTo(3);
        assertThat(counter("ticketing.reservation.success") - successBefore).isEqualTo(2);
        assertThat(counter("ticketing.reservation.idempotency_hits") - idempotencyBefore)
                .isEqualTo(1);
        assertThat(counter("ticketing.reservation.sold_out") - soldOutBefore).isEqualTo(1);
        assertThat(timerCount("ticketing.reservation.duration") - latencyBefore).isEqualTo(3);
    }

    @Test
    @DisplayName("confirmation metrics distinguish committed success from bounded payment failure")
    void confirmationMetricsTrackSuccessAndFailure() {
        double successBefore = counter("ticketing.confirmation.success");
        double declinedBefore = taggedCounter(
                "ticketing.confirmation.failures", "reason", "payment_declined");
        long latencyBefore = timerCount("ticketing.confirmation.duration");

        Reservation paid = reservations.reserve(command(
                UUID.randomUUID(), 1, "metrics-" + UUID.randomUUID()));
        confirmations.confirm(paid.getId(), paid.getUserId(), "tok_ok", "confirm-success");

        Reservation declined = reservations.reserve(command(
                UUID.randomUUID(), 1, "metrics-" + UUID.randomUUID()));
        assertThatThrownBy(() -> confirmations.confirm(
                declined.getId(), declined.getUserId(), "tok_declined", "confirm-declined"))
                .isInstanceOf(PaymentDeclinedException.class);

        assertThat(counter("ticketing.confirmation.success") - successBefore).isEqualTo(1);
        assertThat(taggedCounter(
                "ticketing.confirmation.failures", "reason", "payment_declined")
                - declinedBefore)
                .isEqualTo(1);
        assertThat(timerCount("ticketing.confirmation.duration") - latencyBefore).isEqualTo(2);
    }

    @Test
    @DisplayName("expired-hold metrics increment only after the expiry transaction commits")
    void expiryMetricsTrackCommittedRowsAndBatchLatency() {
        double expiredBefore = counter("ticketing.reservation.expired_holds");
        long latencyBefore = timerCount("ticketing.expiry.batch.duration");

        Reservation reservation = reservations.reserve(command(
                UUID.randomUUID(), 1, "metrics-" + UUID.randomUUID()));
        jdbc().update("UPDATE reservation SET expires_at = now() - INTERVAL '1 second' "
                + "WHERE id = ?::uuid", reservation.getId());

        assertThat(expiryWorker.expireBatch()).isEqualTo(1);
        assertThat(counter("ticketing.reservation.expired_holds") - expiredBefore).isEqualTo(1);
        assertThat(timerCount("ticketing.expiry.batch.duration") - latencyBefore).isEqualTo(1);
    }

    private ReserveTicketsCommand command(UUID userId, int quantity, String key) {
        return new ReserveTicketsCommand(HOT, userId, quantity, key,
                RequestFingerprint.of(HOT, userId, quantity), "day10-metrics");
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private long timerCount(String name) {
        return registry.get(name).timer().count();
    }

    private double taggedCounter(String name, String tag, String value) {
        var counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
    }
}
