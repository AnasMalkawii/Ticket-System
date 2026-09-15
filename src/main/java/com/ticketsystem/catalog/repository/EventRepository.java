package com.ticketsystem.catalog.repository;

import com.ticketsystem.catalog.domain.Event;
import com.ticketsystem.catalog.domain.EventStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, UUID> {

    /** Folds the authoritative database clock into the reserve-path event lookup. */
    @Query(value = """
            SELECT CAST(EXTRACT(EPOCH FROM e.sale_starts_at) * 1000 AS bigint)
                       AS saleStartsAtEpochMillis,
                   CAST(EXTRACT(EPOCH FROM e.sale_ends_at) * 1000 AS bigint)
                       AS saleEndsAtEpochMillis,
                   e.status AS status,
                   CAST(EXTRACT(EPOCH FROM now()) * 1000 AS bigint)
                       AS databaseEpochMillis
            FROM event e
            WHERE e.id = :eventId
            """, nativeQuery = true)
    Optional<EventWithDatabaseTime> findWithDatabaseTimeById(@Param("eventId") UUID eventId);

    /** Unfiltered catalog page using a closed projection and its covering index. */
    Page<EventCatalogView> findAllProjectedBy(Pageable pageable);

    /** Status-filtered projected page using the status/catalog covering index. */
    Page<EventCatalogView> findByStatus(EventStatus status, Pageable pageable);
}
