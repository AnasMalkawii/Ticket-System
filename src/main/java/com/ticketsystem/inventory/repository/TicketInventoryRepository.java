package com.ticketsystem.inventory.repository;

import com.ticketsystem.inventory.domain.TicketInventory;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketInventoryRepository extends JpaRepository<TicketInventory, UUID> {

    /**
     * Acquires an explicit exclusive row lock for lifecycle operations and for the
     * pessimistic reservation comparison branch, emitting {@code SELECT ... FOR UPDATE}.
     *
     * <p>Two rules govern every caller:
     *
     * <ul>
     *   <li><strong>Everything that guards an invariant is evaluated after this returns</strong>,
     *       inside the same transaction. A value read before the lock - from Redis, from an
     *       earlier query, or from a previous request - may never authorise a decrement.</li>
     *   <li><strong>Nothing slow runs while the lock is held.</strong> No HTTP calls, no
     *       payment, no broker publish. Lock hold time is the throughput ceiling for the
     *       event, so work inside it is a tax paid by every other waiting user.</li>
     * </ul>
     *
     * <p>Callers must already be inside a transaction; a lock acquired without one would be
     * released immediately and guarantee nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TicketInventory> findWithLockByEventId(UUID eventId);

    /**
     * Moves available inventory into held inventory in one statement. PostgreSQL takes the
     * row lock while evaluating this UPDATE and rechecks the availability predicate after
     * any concurrent writer commits. Zero affected rows therefore means the event is sold
     * out for this quantity; no read-then-write window exists.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE ticket_inventory
               SET available = available - :quantity,
                   held = held + :quantity,
                   version = version + 1,
                   updated_at = :now
             WHERE event_id = :eventId
               AND available >= :quantity
            """, nativeQuery = true)
    int tryHoldAtomically(@Param("eventId") UUID eventId,
                          @Param("quantity") int quantity,
                          @Param("now") Instant now);
}
