package com.ticketsystem.messaging.publisher;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Thin scheduling shell so tests can disable the timer and drive the worker deterministically. */
@Component
@ConditionalOnProperty(
        prefix = "ticketing.messaging",
        name = {"enabled", "publisher-scheduling-enabled"},
        havingValue = "true")
public class OutboxPublisherScheduler {

    private final OutboxPublisherWorker worker;

    public OutboxPublisherScheduler(OutboxPublisherWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ticketing.messaging.publisher-interval:1s}")
    public void publishReadyEvents() {
        worker.publishBatch();
    }
}
