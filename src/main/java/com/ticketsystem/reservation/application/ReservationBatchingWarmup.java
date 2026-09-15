package com.ticketsystem.reservation.application;

import com.ticketsystem.shared.persistence.TimeOrderedUuid;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Warms the burst-only transaction before the replica reports startup complete. */
@Component
@ConditionalOnProperty(prefix = "ticketing.reservation-batching", name = "enabled",
        havingValue = "true")
public class ReservationBatchingWarmup implements ApplicationRunner {

    private static final int WARMUP_SIZE = 256;

    private final JdbcTemplate jdbc;
    private final BatchReservationTransaction batchTransaction;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    public ReservationBatchingWarmup(JdbcTemplate jdbc,
                                     BatchReservationTransaction batchTransaction,
                                     EntityManager entityManager,
                                     PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.batchTransaction = batchTransaction;
        this.entityManager = entityManager;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        transactions.executeWithoutResult(status -> {
            UUID eventId = TimeOrderedUuid.next();
            jdbc.update("""
                    INSERT INTO event (
                        id, name, venue, starts_at, sale_starts_at, sale_ends_at,
                        status, price_minor, currency)
                    VALUES (?, ?, 'startup-warmup', now() + interval '2 hours',
                            now() - interval '1 minute', now() + interval '1 hour',
                            'ON_SALE', 0, 'USD')
                    """, eventId, "reservation-batch-warmup-" + eventId);
            jdbc.update("""
                    INSERT INTO ticket_inventory (event_id, total, available, held, sold)
                    VALUES (?, ?, ?, 0, 0)
                    """, eventId, WARMUP_SIZE, WARMUP_SIZE);

            List<ReserveTicketsCommand> commands = new ArrayList<>(WARMUP_SIZE);
            for (int index = 0; index < WARMUP_SIZE; index++) {
                UUID userId = TimeOrderedUuid.next();
                commands.add(new ReserveTicketsCommand(
                        eventId,
                        userId,
                        1,
                        "startup-warmup-" + eventId + "-" + index,
                        "startup-warmup"));
            }
            batchTransaction.reserve(eventId, commands);
            entityManager.flush();

            // Exercise the real SQL, constraints and commit preparation without leaving
            // synthetic events, reservations, inventory, or outbox rows behind.
            status.setRollbackOnly();
        });
    }
}
