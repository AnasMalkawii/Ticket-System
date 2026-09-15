package com.ticketsystem.messaging.repository;

import com.ticketsystem.messaging.domain.ProcessedEvent;
import com.ticketsystem.messaging.domain.ProcessedEventId;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    /**
     * Database-level deduplication that remains correct when two replicas receive the same
     * event simultaneously. A check-then-insert in Java would still race.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_event (event_id, consumer, processed_at)
            VALUES (:eventId, :consumer, now())
            ON CONFLICT (event_id, consumer) DO NOTHING
            """, nativeQuery = true)
    int recordIfFirst(@Param("eventId") UUID eventId, @Param("consumer") String consumer);
}
