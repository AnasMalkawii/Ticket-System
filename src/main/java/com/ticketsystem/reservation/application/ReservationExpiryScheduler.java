package com.ticketsystem.reservation.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Production trigger kept separate so integration tests can invoke the worker deterministically. */
@Component
@ConditionalOnProperty(prefix = "ticketing.expiry-worker", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
public class ReservationExpiryScheduler {

    private final ReservationExpiryWorker worker;

    public ReservationExpiryScheduler(ReservationExpiryWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ticketing.expiry-worker.interval:10s}",
            initialDelayString = "${ticketing.expiry-worker.interval:10s}")
    public void expireAbandonedHolds() {
        worker.expireBatch();
    }
}
