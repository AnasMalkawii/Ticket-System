package com.ticketsystem.messaging.publisher;

import com.ticketsystem.messaging.config.MessagingProperties;
import com.ticketsystem.messaging.domain.OutboxEvent;
import com.ticketsystem.messaging.repository.OutboxEventRepository;
import com.ticketsystem.shared.api.RequestCorrelation;
import com.ticketsystem.shared.time.DatabaseTimeProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims unpublished rows, publishes them, and marks each row only after broker ack.
 *
 * <p>A broker failure is contained to this independent transaction. The matching booking
 * transaction has already committed, and the persisted retry schedule survives restarts.
 */
@Service
@ConditionalOnProperty(prefix = "ticketing.messaging", name = "enabled", havingValue = "true")
public class OutboxPublisherWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);

    private final OutboxEventRepository outboxRepository;
    private final RabbitOutboxPublisher publisher;
    private final MessagingProperties properties;
    private final DatabaseTimeProvider databaseTime;

    public OutboxPublisherWorker(OutboxEventRepository outboxRepository,
                                 RabbitOutboxPublisher publisher,
                                 MessagingProperties properties,
                                 DatabaseTimeProvider databaseTime) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.properties = properties;
        this.databaseTime = databaseTime;
    }

    @Transactional
    public PublishBatchResult publishBatch() {
        List<OutboxEvent> claimed = outboxRepository
                .claimReadyForPublish(properties.batchSize());
        int published = 0;
        int failed = 0;

        for (OutboxEvent event : claimed) {
            try (RequestCorrelation.Scope ignored =
                         RequestCorrelation.open(event.getCorrelationId())) {
                publisher.publish(event);
                event.markPublished(databaseTime.now());
                published++;
            } catch (RuntimeException failure) {
                int nextAttempt = event.getAttempts() + 1;
                Duration delay = properties.retryDelayAfter(nextAttempt);
                Instant now = databaseTime.now();
                event.recordFailedAttempt(now, delay, rootMessage(failure));
                failed++;
                log.warn("Outbox publish failed [eventId={}, attempt={}, retryAt={}]",
                        event.getId(), nextAttempt, event.getNextAttemptAt(), failure);

                // A broker outage affects every message. Stop this pass rather than waiting
                // for the confirm timeout once per claimed row and holding locks needlessly.
                break;
            }
        }
        return new PublishBatchResult(claimed.size(), published, failed);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
