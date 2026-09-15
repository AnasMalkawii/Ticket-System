package com.ticketsystem.reservation.application;

import com.ticketsystem.catalog.domain.EventNotFoundException;
import com.ticketsystem.catalog.domain.EventStatus;
import com.ticketsystem.catalog.domain.SaleEndedException;
import com.ticketsystem.catalog.domain.SaleNotStartedException;
import com.ticketsystem.catalog.repository.EventRepository;
import com.ticketsystem.catalog.repository.EventWithDatabaseTime;
import com.ticketsystem.inventory.domain.InsufficientInventoryException;
import com.ticketsystem.inventory.domain.TicketInventory;
import com.ticketsystem.inventory.repository.TicketInventoryRepository;
import com.ticketsystem.reservation.domain.IdempotencyKeyConflictException;
import com.ticketsystem.reservation.domain.InvalidQuantityException;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.UserLimitExceededException;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.reservation.repository.UserActiveQuantity;
import com.ticketsystem.shared.config.TicketingProperties;
import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.persistence.TimeOrderedUuid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits many holds for one event under one inventory lock and one database transaction.
 * The row remains the authority; batching merely amortizes its lock and commit cost.
 */
@Component
public class BatchReservationTransaction {

    private final EventRepository events;
    private final TicketInventoryRepository inventories;
    private final ReservationRepository reservations;
    private final BatchReservationJdbcWriter batchWriter;
    private final TicketingProperties properties;

    public BatchReservationTransaction(EventRepository events,
                                       TicketInventoryRepository inventories,
                                       ReservationRepository reservations,
                                       BatchReservationJdbcWriter batchWriter,
                                       TicketingProperties properties) {
        this.events = events;
        this.inventories = inventories;
        this.reservations = reservations;
        this.batchWriter = batchWriter;
        this.properties = properties;
    }

    @Transactional
    public List<BatchReservationOutcome> reserve(UUID eventId,
                                                 List<ReserveTicketsCommand> commands) {
        EventWithDatabaseTime event = events.findWithDatabaseTimeById(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        Instant now = Instant.ofEpochMilli(event.getDatabaseEpochMillis());
        requireSaleOpen(event, eventId, now);

        int maximum = properties.reservation().maxQuantityPerRequest();
        List<PreparedCommand> prepared = new ArrayList<>(commands.size());
        Set<String> keys = new LinkedHashSet<>();
        Set<UUID> users = new LinkedHashSet<>();
        for (ReserveTicketsCommand command : commands) {
            try {
                IdempotencyKeyPolicy.validate(command.idempotencyKey());
                int quantity = command.quantity();
                if (quantity < 1 || quantity > maximum) {
                    throw new InvalidQuantityException(quantity, maximum);
                }
                String fingerprint = RequestFingerprint.of(
                        command.eventId(), command.userId(), quantity);
                Reservation candidate = Reservation.pendingWithId(
                        TimeOrderedUuid.next(), eventId, command.userId(), quantity,
                        command.idempotencyKey(), fingerprint,
                        properties.reservation().ttl(), now);
                prepared.add(PreparedCommand.valid(command, fingerprint, candidate,
                        batchWriter.prepare(candidate, command.correlationId())));
                keys.add(command.idempotencyKey());
                users.add(command.userId());
            } catch (DomainException rejected) {
                prepared.add(PreparedCommand.invalid(command, rejected));
            }
        }

        // One event row serializes competing batches and lifecycle mutations. Hundreds of
        // successful holds below produce one counter UPDATE and one commit instead of one
        // lock/commit pair per HTTP request. Pure Java validation, UUID generation and JSON
        // serialization happen above so other replicas wait on this row for less time.
        TicketInventory inventory = inventories.findWithLockByEventId(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        requireSaleOpen(event, eventId, now);

        Map<String, Reservation> byKey = new HashMap<>();
        if (!keys.isEmpty()) {
            reservations.findAllByIdempotencyKeyIn(keys)
                    .forEach(reservation -> byKey.put(
                            reservation.getIdempotencyKey(), reservation));
        }

        Map<UUID, Integer> activeByUser = new HashMap<>();
        if (!users.isEmpty()) {
            for (UserActiveQuantity quantity : reservations.sumActiveQtyForUsers(eventId, users)) {
                activeByUser.put(quantity.getUserId(), Math.toIntExact(quantity.getQuantity()));
            }
        }

        int remaining = inventory.getAvailable();
        int cap = properties.reservation().perUserEventCap();
        List<BatchReservationOutcome> outcomes = new ArrayList<>(commands.size());
        List<BatchReservationJdbcWriter.PendingReservation> pending = new ArrayList<>();

        for (PreparedCommand item : prepared) {
            ReserveTicketsCommand command = item.command();
            if (item.failure() != null) {
                outcomes.add(BatchReservationOutcome.failed(item.failure()));
                continue;
            }
            try {
                int quantity = command.quantity();
                Reservation existing = byKey.get(command.idempotencyKey());
                if (existing != null) {
                    if (!existing.matchesFingerprint(item.fingerprint())) {
                        throw new IdempotencyKeyConflictException(command.idempotencyKey());
                    }
                    outcomes.add(BatchReservationOutcome.succeeded(
                            ReserveTicketsResult.replayed(existing)));
                    continue;
                }

                int alreadyHeld = activeByUser.getOrDefault(command.userId(), 0);
                if (alreadyHeld + quantity > cap) {
                    throw new UserLimitExceededException(command.userId(), eventId,
                            alreadyHeld, quantity, cap);
                }
                if (remaining < quantity) {
                    throw new InsufficientInventoryException(eventId, quantity, remaining);
                }

                Reservation created = item.candidate();
                pending.add(item.pending());
                inventory.hold(quantity, now);

                remaining -= quantity;
                activeByUser.put(command.userId(), alreadyHeld + quantity);
                byKey.put(command.idempotencyKey(), created);
                outcomes.add(BatchReservationOutcome.succeeded(
                        ReserveTicketsResult.created(created)));
            } catch (DomainException rejected) {
                outcomes.add(BatchReservationOutcome.failed(rejected));
            }
        }
        batchWriter.insert(pending, now);
        return outcomes;
    }

    private record PreparedCommand(ReserveTicketsCommand command,
                                   String fingerprint,
                                   Reservation candidate,
                                   BatchReservationJdbcWriter.PendingReservation pending,
                                   DomainException failure) {

        private static PreparedCommand valid(
                ReserveTicketsCommand command,
                String fingerprint,
                Reservation candidate,
                BatchReservationJdbcWriter.PendingReservation pending) {
            return new PreparedCommand(command, fingerprint, candidate, pending, null);
        }

        private static PreparedCommand invalid(
                ReserveTicketsCommand command, DomainException failure) {
            return new PreparedCommand(command, null, null, null, failure);
        }
    }

    private void requireSaleOpen(EventWithDatabaseTime event, UUID eventId, Instant now) {
        Instant saleStartsAt = Instant.ofEpochMilli(event.getSaleStartsAtEpochMillis());
        Long saleEndsAtMillis = event.getSaleEndsAtEpochMillis();
        if (now.isBefore(saleStartsAt)) {
            throw new SaleNotStartedException(eventId, saleStartsAt);
        }
        if ((saleEndsAtMillis != null && now.toEpochMilli() >= saleEndsAtMillis)
                || !EventStatus.valueOf(event.getStatus()).allowsReservation()) {
            throw new SaleEndedException(eventId);
        }
    }
}
