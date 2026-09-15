package com.ticketsystem.reservation.repository;

import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Idempotency replay lookup (I3). Backed by the UNIQUE index on idempotency_key, which
     * is also what arbitrates two simultaneous inserts of the same key (F-01).
     */
    Optional<Reservation> findByIdempotencyKey(String idempotencyKey);

    /** One lookup for every idempotency key in a micro-batch. */
    List<Reservation> findAllByIdempotencyKeyIn(Collection<String> idempotencyKeys);

    /**
     * Serialises cancellation, confirmation, and expiry for one hold. Every lifecycle
     * transaction takes this reservation lock before its event inventory lock (F-06).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Reservation r WHERE r.id = :reservationId")
    Optional<Reservation> findWithLockById(@Param("reservationId") UUID reservationId);

    /**
     * Total tickets a user already holds or owns for an event, used for the per-user cap
     * (I6). Must be executed inside the same reserve transaction after that user's/event's
     * serialization lock is acquired; the micro-batch path instead holds the event inventory
     * lock while performing its grouped cap query.
     *
     * <p>Served as an index-only scan by ix_reservation_user_event_status.
     */
    @Query("""
            SELECT COALESCE(SUM(r.qty), 0)
            FROM Reservation r
            WHERE r.userId = :userId
              AND r.eventId = :eventId
              AND r.status IN :statuses
            """)
    int sumQtyByUserAndEventAndStatusIn(@Param("userId") UUID userId,
                                        @Param("eventId") UUID eventId,
                                        @Param("statuses") Collection<ReservationStatus> statuses);

    /** Convenience overload using the statuses that count toward a user's cap. */
    default int sumActiveQtyForUser(UUID userId, UUID eventId) {
        return sumQtyByUserAndEventAndStatusIn(userId, eventId,
                java.util.Arrays.stream(ReservationStatus.values())
                        .filter(ReservationStatus::countsTowardUserLimit)
                        .toList());
    }

    /** One grouped query replaces a per-request cap query inside a micro-batch. */
    @Query(value = """
            SELECT r.user_id AS userId, SUM(r.qty) AS quantity
            FROM reservation r
            WHERE r.event_id = :eventId
              AND r.user_id IN (:userIds)
              AND r.status IN ('PENDING', 'CONFIRMED')
            GROUP BY r.user_id
            """, nativeQuery = true)
    List<UserActiveQuantity> sumActiveQtyForUsers(@Param("eventId") UUID eventId,
                                                   @Param("userIds") Collection<UUID> userIds);

    /**
     * Claims a batch of expired holds for release.
     *
     * <p>Native SQL rather than JPQL for two reasons that both matter for correctness:
     * {@code SKIP LOCKED} lets several replicas run the worker concurrently without either
     * blocking or double-releasing (F-05), and the literal {@code 'PENDING'} lets the
     * planner match the partial index ix_reservation_pending_expiry, which a bind parameter
     * would prevent.
     *
     * <p>{@code now()} is database time, so a skewed replica clock cannot expire live holds
     * (F-18). {@code ReservationExpiryWorker} processes the claimed rows in the same
     * transaction and locks event inventory in deterministic order.
     */
    @Query(value = """
            SELECT id FROM reservation
            WHERE status = 'PENDING'
              AND expires_at <= now()
            ORDER BY expires_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> claimExpiredPendingIds(@Param("batchSize") int batchSize);
}
