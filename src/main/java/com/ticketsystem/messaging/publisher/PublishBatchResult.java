package com.ticketsystem.messaging.publisher;

/** Observable result of one outbox polling pass. */
public record PublishBatchResult(int claimed, int published, int failed) {
}
