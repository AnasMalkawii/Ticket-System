package com.ticketsystem.reservation.application;

import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.inventory.domain.TicketInventory;
import com.ticketsystem.inventory.repository.TicketInventoryRepository;
import com.ticketsystem.observability.TicketingMetrics;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.shared.api.RequestCorrelation;
import com.ticketsystem.shared.config.TicketingProperties;
import com.ticketsystem.shared.time.DatabaseTimeProvider;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Claims and expires one bounded batch of abandoned holds.
 *
 * <p>The claim query owns the exact-once guarantee: {@code FOR UPDATE SKIP LOCKED} means
 * another replica either claims a different row or skips this transaction's rows. Claimed
 * reservations are grouped by event, and inventory rows are locked in UUID order, so two
 * mixed-event batches cannot deadlock by visiting hot inventory rows in opposite orders.
 */
@Service
public class ReservationExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryWorker.class);

    private final ReservationRepository reservationRepository;
    private final TicketInventoryRepository inventoryRepository;
    private final DatabaseTimeProvider databaseTime;
    private final TicketingProperties properties;
    private final ReservationOutboxWriter outboxWriter;
    private final TicketingMetrics metrics;
    private final TransactionTemplate transaction;

    public ReservationExpiryWorker(ReservationRepository reservationRepository,
                                   TicketInventoryRepository inventoryRepository,
                                   DatabaseTimeProvider databaseTime,
                                   TicketingProperties properties,
                                   ReservationOutboxWriter outboxWriter,
                                   TicketingMetrics metrics,
                                   PlatformTransactionManager transactionManager) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
        this.databaseTime = databaseTime;
        this.properties = properties;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** @return number of holds transitioned to {@code EXPIRED} in this batch */
    public int expireBatch() {
        String correlationId = RequestCorrelation.currentId()
                .orElseGet(() -> "expiry-" + UUID.randomUUID());
        try (RequestCorrelation.Scope ignored = RequestCorrelation.open(correlationId)) {
            Timer.Sample sample = metrics.startExpiryBatch();
            try {
                Integer expired = transaction.execute(
                        status -> expireBatchInTransaction(correlationId));
                int count = expired == null ? 0 : expired;
                metrics.expiredHolds(count);
                if (count > 0) {
                    log.atInfo()
                            .addKeyValue("expiredHolds", count)
                            .log("Expiry batch committed");
                }
                return count;
            } catch (RuntimeException failure) {
                metrics.expiryFailed();
                log.atError().setCause(failure).log("Expiry batch rolled back");
                throw failure;
            } finally {
                metrics.stopExpiryBatch(sample);
            }
        }
    }

    private int expireBatchInTransaction(String correlationId) {
        Instant now = databaseTime.now();
        List<UUID> claimedIds = reservationRepository
                .claimExpiredPendingIds(properties.expiryWorker().batchSize());
        if (claimedIds.isEmpty()) {
            return 0;
        }

        List<Reservation> claimed = new ArrayList<>(reservationRepository.findAllById(claimedIds));
        if (claimed.size() != claimedIds.size()) {
            throw new IllegalStateException(
                    "An expiry claim disappeared while its row lock was held");
        }

        Map<UUID, Integer> quantityByEvent = new TreeMap<>();
        for (Reservation reservation : claimed) {
            // The native claim and this guard both use the same transaction's database now().
            // The second check makes a faulty query incapable of releasing a live hold.
            reservation.expire(now);
            quantityByEvent.merge(reservation.getEventId(), reservation.getQty(), Math::addExact);
        }

        for (Map.Entry<UUID, Integer> release : quantityByEvent.entrySet()) {
            TicketInventory inventory = inventoryRepository.findWithLockByEventId(release.getKey())
                    .orElseThrow(() -> new EventNotFoundException(release.getKey()));
            inventory.releaseHold(release.getValue(), now);
        }

        for (Reservation reservation : claimed) {
            outboxWriter.expired(reservation, correlationId, now);
        }
        return claimed.size();
    }
}
