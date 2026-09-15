package com.ticketsystem.reservation.application;

import com.ticketsystem.inventory.domain.InsufficientInventoryException;
import com.ticketsystem.observability.TicketingMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Collects a short burst per event and hands it to one database transaction. */
@Component
public class ReservationBatcher {

    private final ConcurrentHashMap<UUID, EventQueue> queues = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final BatchReservationTransaction batchTransaction;
    private final ReserveTicketsService directService;
    private final ReservationBatchingProperties properties;
    private final TicketingMetrics metrics;

    public ReservationBatcher(BatchReservationTransaction batchTransaction,
                              ReserveTicketsService directService,
                              ReservationBatchingProperties properties,
                              TicketingMetrics metrics) {
        this.batchTransaction = batchTransaction;
        this.directService = directService;
        this.properties = properties;
        this.metrics = metrics;
    }

    public ReserveTicketsResult reserve(ReserveTicketsCommand command) {
        Timer.Sample sample = metrics.startReservation();
        CompletableFuture<ReserveTicketsResult> future = new CompletableFuture<>();
        EventQueue queue = queues.compute(command.eventId(), (ignored, current) -> {
            EventQueue target = current == null ? new EventQueue() : current;
            target.items.add(new Work(command, future));
            return target;
        });
        schedule(command.eventId(), queue);

        try {
            ReserveTicketsResult result = future.get();
            metrics.reservationSucceeded(result.replayed());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IllegalStateException failure = new IllegalStateException(
                    "Interrupted while waiting for reservation batch", interrupted);
            metrics.reservationFailed(failure);
            throw failure;
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof InsufficientInventoryException soldOut) {
                metrics.reservationSoldOut();
                throw soldOut;
            }
            RuntimeException failure = cause instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException(cause);
            metrics.reservationFailed(failure);
            throw failure;
        } finally {
            metrics.stopReservation(sample);
        }
    }

    private void schedule(UUID eventId, EventQueue queue) {
        if (queue.draining.compareAndSet(false, true)) {
            workers.submit(() -> drain(eventId, queue));
        }
    }

    private void drain(UUID eventId, EventQueue queue) {
        try {
            // Pay the coalescing window once when a previously idle event becomes active.
            // If more work arrived while a transaction was running, it is already a batch:
            // sleeping again only creates head-of-line latency for the queued callers.
            LockSupport.parkNanos(properties.window().toNanos());
            while (true) {
                List<Work> batch = new ArrayList<>(properties.maxBatchSize());
                while (batch.size() < properties.maxBatchSize()) {
                    Work work = queue.items.poll();
                    if (work == null) {
                        break;
                    }
                    batch.add(work);
                }
                if (!batch.isEmpty()) {
                    execute(eventId, batch);
                }
                if (queue.items.isEmpty()) {
                    queue.draining.set(false);
                    queues.computeIfPresent(eventId, (ignored, current) ->
                            current == queue && queue.items.isEmpty() ? null : current);
                    if (!queue.items.isEmpty()) {
                        schedule(eventId, queue);
                    }
                    return;
                }
            }
        } catch (Throwable failure) {
            Work work;
            while ((work = queue.items.poll()) != null) {
                work.future().completeExceptionally(failure);
            }
            queue.draining.set(false);
        }
    }

    private void execute(UUID eventId, List<Work> batch) {
        try {
            List<ReserveTicketsCommand> commands = batch.stream().map(Work::command).toList();
            List<BatchReservationOutcome> outcomes = batchTransaction.reserve(eventId, commands);
            for (int index = 0; index < batch.size(); index++) {
                BatchReservationOutcome outcome = outcomes.get(index);
                if (outcome.failure() == null) {
                    batch.get(index).future().complete(outcome.result());
                } else {
                    batch.get(index).future().completeExceptionally(outcome.failure());
                }
            }
        } catch (RuntimeException batchFailure) {
            // A rare cross-event idempotency collision or batch-wide database failure must
            // not make unrelated requests collateral damage. The established one-at-a-time
            // path remains the correctness-preserving fallback.
            for (Work work : batch) {
                try {
                    work.future().complete(directService.reserveWithResult(work.command()));
                } catch (RuntimeException itemFailure) {
                    work.future().completeExceptionally(itemFailure);
                }
            }
        }
    }

    @PreDestroy
    void close() {
        workers.shutdown();
    }

    private record Work(ReserveTicketsCommand command,
                        CompletableFuture<ReserveTicketsResult> future) {
    }

    private static final class EventQueue {
        private final ConcurrentLinkedQueue<Work> items = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();
    }
}
