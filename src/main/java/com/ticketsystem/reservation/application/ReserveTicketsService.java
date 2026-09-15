package com.ticketsystem.reservation.application;

import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.catalog.domain.EventStatus;
import com.ticketsystem.catalog.domain.SaleEndedException;
import com.ticketsystem.catalog.domain.SaleNotStartedException;
import com.ticketsystem.catalog.repository.EventRepository;
import com.ticketsystem.catalog.repository.EventWithDatabaseTime;
import com.ticketsystem.inventory.domain.TicketInventory;
import com.ticketsystem.inventory.domain.InsufficientInventoryException;
import com.ticketsystem.inventory.repository.TicketInventoryRepository;
import com.ticketsystem.observability.TicketingMetrics;
import com.ticketsystem.reservation.domain.IdempotencyKeyConflictException;
import com.ticketsystem.reservation.domain.IdempotencyInProgressException;
import com.ticketsystem.reservation.domain.InvalidQuantityException;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.UserLimitExceededException;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.shared.config.TicketingProperties;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The zero-oversell reserve transaction.
 *
 * <p>This is the only place in the system that decrements available inventory, and the whole
 * design turns on the ordering below. The naive implementation - read availability, decide,
 * then update - is not merely risky under {@code READ COMMITTED}; at 500 concurrent users it
 * oversells reliably, because another transaction commits between the read and the write.
 *
 * <p>The ordering here removes that window:
 *
 * <ol>
 *   <li><strong>Cheap rejections first.</strong> Quantity bounds, then the event lookup and
 *       sale window. A request that can never succeed is rejected before it queues on the
 *       contended inventory row, keeping doomed flash-sale traffic off the hot path.</li>
 *   <li><strong>Insert the hold, claiming the idempotency key.</strong> Done before the lock
 *       so a duplicate retry collides on the unique index instead of joining the queue for a
 *       row it has no business touching (F-01).</li>
 *   <li><strong>Serialize only the same user.</strong> A transaction-scoped advisory lock for
 *       user/event protects the per-user cap without forcing unrelated users through the
 *       hot inventory row.</li>
 *   <li><strong>Prepare the ledger and outbox writes.</strong> They are flushed before the
 *       contended inventory update.</li>
 *   <li><strong>Move inventory last.</strong> The conditional UPDATE acquires the row lock,
 *       rechecks availability, and is followed immediately by commit.</li>
 * </ol>
 *
 * <p>Nothing slow happens inside the lock: no HTTP call, no payment, no broker publish. The
 * outbox row is written in the same transaction instead, which is also what removes the
 * database/broker dual write (F-08).
 *
 * <p>Idempotent replay deliberately wraps the transaction rather than catching a duplicate
 * key inside it. PostgreSQL aborts a transaction after a unique violation, so the losing
 * attempt must roll back completely before a fresh transaction can read and replay the
 * winner. This also handles concurrent replicas: the unique index, not JVM memory, chooses
 * the one logical reservation.
 */
@Service
public class ReserveTicketsService {

    private final EventRepository eventRepository;
    private final TicketInventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationOutboxWriter outboxWriter;
    private final TicketingProperties properties;
    private final TicketingMetrics metrics;
    private final EntityManager entityManager;
    private final TransactionTemplate creationTransaction;
    private final TransactionTemplate replayReadTransaction;

    public ReserveTicketsService(EventRepository eventRepository,
                                 TicketInventoryRepository inventoryRepository,
                                 ReservationRepository reservationRepository,
                                 ReservationOutboxWriter outboxWriter,
                                 TicketingProperties properties,
                                 TicketingMetrics metrics,
                                 EntityManager entityManager,
                                 PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.metrics = metrics;
        this.entityManager = entityManager;
        this.creationTransaction = new TransactionTemplate(transactionManager);
        this.creationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.replayReadTransaction = new TransactionTemplate(transactionManager);
        this.replayReadTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.replayReadTransaction.setReadOnly(true);
    }

    /**
     * Backwards-compatible domain-facing form. Call {@link #reserveWithResult} when replay
     * metadata is needed at the transport boundary.
     */
    public Reservation reserve(ReserveTicketsCommand command) {
        return reserveWithResult(command).reservation();
    }

    /** Creates the hold or replays the one already owned by this idempotency key. */
    public ReserveTicketsResult reserveWithResult(ReserveTicketsCommand command) {
        Timer.Sample sample = metrics.startReservation();
        try {
            ReserveTicketsResult result = reserveOnce(command);
            metrics.reservationSucceeded(result.replayed());
            return result;
        } catch (InsufficientInventoryException soldOut) {
            metrics.reservationSoldOut();
            throw soldOut;
        } catch (RuntimeException failure) {
            metrics.reservationFailed(failure);
            throw failure;
        } finally {
            metrics.stopReservation(sample);
        }
    }

    private ReserveTicketsResult reserveOnce(ReserveTicketsCommand command) {
        IdempotencyKeyPolicy.validate(command.idempotencyKey());

        try {
            // Claim first and let the database UNIQUE index arbitrate duplicates. Reading
            // before inserting adds a transaction and a round trip to every new booking,
            // while still being unable to eliminate the insert race. A duplicate rolls
            // this transaction back and is replayed by the fresh read below.
            Reservation created = creationTransaction.execute(status -> createReservation(command));
            if (created == null) {
                throw new IllegalStateException("Reservation transaction returned no result");
            }
            return ReserveTicketsResult.created(created);
        } catch (DataIntegrityViolationException duplicateOrConstraintFailure) {
            // The failed transaction is fully rolled back at this point. If a row now owns
            // the key, a concurrent request committed first and its result is authoritative.
            Optional<Reservation> winner = findByIdempotencyKey(command.idempotencyKey());
            if (winner.isPresent()) {
                return replay(command, winner.get());
            }
            throw duplicateOrConstraintFailure;
        } catch (RuntimeException creationFailure) {
            if (creationFailure instanceof IdempotencyClaimTimeoutException) {
                // The winner may have committed between PostgreSQL raising the timeout and
                // this fresh read. Prefer a definitive replay when it is already visible.
                Optional<Reservation> winner = findByIdempotencyKey(command.idempotencyKey());
                if (winner.isPresent()) {
                    return replay(command, winner.get());
                }
                throw new IdempotencyInProgressException(retryAfterSeconds());
            }
            throw creationFailure;
        }
    }

    private Reservation createReservation(ReserveTicketsCommand command) {
        int quantity = command.quantity();
        int maxQuantity = properties.reservation().maxQuantityPerRequest();
        if (quantity < 1 || quantity > maxQuantity) {
            throw new InvalidQuantityException(quantity, maxQuantity);
        }

        // Fetch the event and the transaction's database time together. PostgreSQL's
        // CURRENT_TIMESTAMP is stable for the transaction, preserving clock-skew safety
        // without paying for a second database round trip.
        EventWithDatabaseTime eventContext = eventRepository
                .findWithDatabaseTimeById(command.eventId())
                .orElseThrow(() -> new EventNotFoundException(command.eventId()));
        Instant now = Instant.ofEpochMilli(eventContext.getDatabaseEpochMillis());
        requireSaleOpen(eventContext, command.eventId(), now);

        // Claim the idempotency key before contending for the inventory row. A duplicate
        // retry blocks here on the unique index rather than lengthening the lock queue.
        Reservation reservation = Reservation.pending(
                command.eventId(), command.userId(), quantity, command.idempotencyKey(),
                canonicalFingerprint(command), properties.reservation().ttl(), now);

        // A duplicate INSERT waits on PostgreSQL's unique index while the first transaction
        // is unresolved. Bound only that claim wait, then restore the ordinary lock timeout
        // before inventory contention so the two kinds of wait keep their distinct meaning.
        setLocalLockTimeout(properties.reservation().idempotencyWaitTimeout());
        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (RuntimeException claimFailure) {
            if (isLockTimeout(claimFailure)) {
                throw new IdempotencyClaimTimeoutException(claimFailure);
            }
            throw claimFailure;
        }
        restoreDefaultLockTimeout();

        // The pessimistic comparison branch still uses the event-wide inventory lock to
        // serialize both inventory and the per-user cap. The production ATOMIC branch uses
        // a much narrower user/event advisory lock for the cap, prepares every other write,
        // and takes the hot inventory-row lock only for the final UPDATE + COMMIT.
        boolean pessimistic = properties.reservation().inventoryStrategy()
                == InventoryReservationStrategy.PESSIMISTIC;
        if (pessimistic) {
            TicketInventory lockedInventory = inventoryRepository.findWithLockByEventId(
                            command.eventId())
                    .orElseThrow(() -> new EventNotFoundException(command.eventId()));
            requireSaleOpen(eventContext, command.eventId(), now);
            requireWithinUserLimit(command, quantity);
            // The comparison branch retains the original SELECT FOR UPDATE behavior.
            // Dirty checking writes the counters when the transaction commits.
            lockedInventory.hold(quantity, now);
            outboxWriter.created(reservation, command.correlationId(), now);
            return reservation;
        }

        // Concurrent requests for the same user/event must not race the cap query. This
        // transaction-scoped advisory lock is independent for different users, so a flash
        // sale no longer serializes every user before it reaches the inventory update.
        lockUserEvent(command.eventId(), command.userId());
        requireWithinUserLimit(command, quantity);
        requireSaleOpen(eventContext, command.eventId(), now);

        // tryHoldAtomically flushes pending entities before issuing its native UPDATE. Put
        // the outbox row in the persistence context now so the reservation and outbox INSERTs
        // complete before the hot inventory lock is taken.
        outboxWriter.created(reservation, command.correlationId(), now);

        // ---- hot inventory critical section begins -----------------------------------
        // The UPDATE itself acquires the event row lock and rechecks availability after any
        // competing transaction commits. No query or INSERT follows a successful update;
        // the transaction returns immediately and commits, minimizing lock hold time.
        int updated = inventoryRepository.tryHoldAtomically(command.eventId(), quantity, now);
        if (updated == 0) {
            TicketInventory snapshot = inventoryRepository.findById(command.eventId())
                    .orElseThrow(() -> new EventNotFoundException(command.eventId()));
            throw new InsufficientInventoryException(
                    command.eventId(), quantity, snapshot.getAvailable());
        }
        // ---- hot inventory critical section ends at COMMIT ---------------------------

        return reservation;
    }

    private void requireWithinUserLimit(ReserveTicketsCommand command, int quantity) {
        // The reservation INSERT is already flushed, so this sum includes the current
        // request and asks whether accepting it would move the user over the configured cap.
        int totalIncludingThisRequest =
                reservationRepository.sumActiveQtyForUser(command.userId(), command.eventId());
        int cap = properties.reservation().perUserEventCap();
        if (totalIncludingThisRequest > cap) {
            throw new UserLimitExceededException(command.userId(), command.eventId(),
                    totalIncludingThisRequest - quantity, quantity, cap);
        }
    }

    private void lockUserEvent(java.util.UUID eventId, java.util.UUID userId) {
        long lockKey = eventId.getMostSignificantBits()
                ^ eventId.getLeastSignificantBits()
                ^ Long.rotateLeft(userId.getMostSignificantBits(), 17)
                ^ Long.rotateLeft(userId.getLeastSignificantBits(), 41);
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, lockKey)
                .getSingleResult();
    }

    private Optional<Reservation> findByIdempotencyKey(String idempotencyKey) {
        Optional<Reservation> result = replayReadTransaction.execute(
                status -> reservationRepository.findByIdempotencyKey(idempotencyKey));
        return result == null ? Optional.empty() : result;
    }

    private ReserveTicketsResult replay(ReserveTicketsCommand command, Reservation existing) {
        if (!existing.matchesFingerprint(canonicalFingerprint(command))) {
            throw new IdempotencyKeyConflictException(command.idempotencyKey());
        }
        return ReserveTicketsResult.replayed(existing);
    }

    private String canonicalFingerprint(ReserveTicketsCommand command) {
        return RequestFingerprint.of(command.eventId(), command.userId(), command.quantity());
    }

    private void setLocalLockTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("idempotency-wait-timeout must be positive");
        }
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
                .setParameter(1, timeout.toMillis() + "ms")
                .getSingleResult();
    }

    private void restoreDefaultLockTimeout() {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', ?1, true)")
                .setParameter(1, properties.database().lockTimeout().toMillis() + "ms")
                .getSingleResult();
    }

    private int retryAfterSeconds() {
        long millis = properties.reservation().idempotencyWaitTimeout().toMillis();
        return Math.toIntExact(Math.max(1, Math.ceilDiv(millis, 1_000)));
    }

    private boolean isLockTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("canceling statement due to lock timeout")) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    /** Marker used only after the unique-key claim times out, never for inventory contention. */
    private static final class IdempotencyClaimTimeoutException extends RuntimeException {

        private IdempotencyClaimTimeoutException(Throwable cause) {
            super(cause);
        }
    }


    private void requireSaleOpen(EventWithDatabaseTime event, java.util.UUID eventId, Instant now) {
        Instant saleStartsAt = Instant.ofEpochMilli(event.getSaleStartsAtEpochMillis());
        Long saleEndsAtMillis = event.getSaleEndsAtEpochMillis();
        Instant saleEndsAt = saleEndsAtMillis == null
                ? null : Instant.ofEpochMilli(saleEndsAtMillis);
        if (now.isBefore(saleStartsAt)) {
            throw new SaleNotStartedException(eventId, saleStartsAt);
        }
        if ((saleEndsAt != null && !now.isBefore(saleEndsAt))
                || !EventStatus.valueOf(event.getStatus()).allowsReservation()) {
            throw new SaleEndedException(eventId);
        }
    }

}
