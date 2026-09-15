package com.ticketsystem.messaging.repository;

import com.ticketsystem.messaging.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims ready rows without making publisher replicas wait for one another.
     *
     * <p>The row locks are held by {@code OutboxPublisherWorker}'s transaction while each
     * message waits for its broker acknowledgement. A crash after the ack but before the
     * database commit can produce a redelivery, which consumers deliberately deduplicate.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE published_at IS NULL
              AND next_attempt_at <= now()
            ORDER BY created_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimReadyForPublish(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();
}
