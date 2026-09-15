package com.ticketsystem.observability;

import com.ticketsystem.shared.error.DomainException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Low-cardinality business outcomes and transaction latency for operational diagnosis. */
@Component
public class TicketingMetrics {

    private final MeterRegistry registry;
    private final Counter reservationAttempts;
    private final Counter reservationSuccess;
    private final Counter reservationSoldOut;
    private final Counter idempotencyHits;
    private final Counter confirmationSuccess;
    private final Counter expiredHolds;
    private final Counter expiryFailures;
    private final Timer reservationDuration;
    private final Timer confirmationDuration;
    private final Timer expiryBatchDuration;

    public TicketingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.reservationAttempts = counter("ticketing.reservation.attempts",
                "Reservation service calls, including retries and rejections");
        this.reservationSuccess = counter("ticketing.reservation.success",
                "Successful reservation responses, including idempotent replays");
        this.reservationSoldOut = counter("ticketing.reservation.sold_out",
                "Reservation attempts rejected because inventory was exhausted");
        this.idempotencyHits = counter("ticketing.reservation.idempotency_hits",
                "Successful reservation responses served from an existing idempotency key");
        this.confirmationSuccess = counter("ticketing.confirmation.success",
                "Reservations successfully confirmed and converted to orders");
        this.expiredHolds = counter("ticketing.reservation.expired_holds",
                "Reservation holds committed as expired by the expiry worker");
        this.expiryFailures = counter("ticketing.expiry.failures",
                "Expiry batches that failed and rolled back");
        this.reservationDuration = timer("ticketing.reservation.duration",
                "End-to-end reservation transaction latency");
        this.confirmationDuration = timer("ticketing.confirmation.duration",
                "End-to-end confirmation latency, including bounded payment");
        this.expiryBatchDuration = timer("ticketing.expiry.batch.duration",
                "Expiry batch latency, including transaction commit");
    }

    public Timer.Sample startReservation() {
        reservationAttempts.increment();
        return Timer.start(registry);
    }

    public void reservationSucceeded(boolean idempotentReplay) {
        reservationSuccess.increment();
        if (idempotentReplay) {
            idempotencyHits.increment();
        }
    }

    public void reservationSoldOut() {
        reservationSoldOut.increment();
    }

    public void reservationFailed(Throwable failure) {
        failureCounter("ticketing.reservation.failures", failure).increment();
    }

    public void stopReservation(Timer.Sample sample) {
        sample.stop(reservationDuration);
    }

    public Timer.Sample startConfirmation() {
        return Timer.start(registry);
    }

    public void confirmationSucceeded() {
        confirmationSuccess.increment();
    }

    public void confirmationFailed(Throwable failure) {
        failureCounter("ticketing.confirmation.failures", failure).increment();
    }

    public void stopConfirmation(Timer.Sample sample) {
        sample.stop(confirmationDuration);
    }

    public Timer.Sample startExpiryBatch() {
        return Timer.start(registry);
    }

    public void expiredHolds(int count) {
        if (count > 0) {
            expiredHolds.increment(count);
        }
    }

    public void expiryFailed() {
        expiryFailures.increment();
    }

    public void stopExpiryBatch(Timer.Sample sample) {
        sample.stop(expiryBatchDuration);
    }

    private Counter failureCounter(String name, Throwable failure) {
        return Counter.builder(name)
                .description("Failed operations grouped by bounded domain reason")
                .tag("reason", failureReason(failure))
                .register(registry);
    }

    private static String failureReason(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof DomainException domainFailure) {
                return domainFailure.errorCode().name().toLowerCase(Locale.ROOT);
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return "unexpected";
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private Timer timer(String name, String description) {
        return Timer.builder(name)
                .description(description)
                .publishPercentileHistogram()
                .minimumExpectedValue(java.time.Duration.ofMillis(1))
                .maximumExpectedValue(java.time.Duration.ofSeconds(10))
                .register(registry);
    }
}
