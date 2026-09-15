package com.ticketsystem.reservation.application;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

/**
 * Runs N reserve attempts that all start at the same instant.
 *
 * <p>The barrier is the point of the harness. Without it, threads trickle in as the executor
 * schedules them and the transactions barely overlap, so the test passes whether or not the
 * locking is correct - the classic false negative in concurrency testing. Releasing every
 * thread simultaneously after each has already built its command maximises the window in
 * which two transactions could interleave.
 */
final class ConcurrentReserveHarness {

    record Outcome(int accepted,
                   int acceptedQuantity,
                   Map<ErrorCode, Integer> rejectionsByCode,
                   List<Throwable> unexpectedFailures,
                   Duration wallClock) {

        int rejected(ErrorCode code) {
            return rejectionsByCode.getOrDefault(code, 0);
        }

        int totalRejected() {
            return rejectionsByCode.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private ConcurrentReserveHarness() {
    }

    static Outcome run(int attempts,
                       IntFunction<ReserveTicketsCommand> commandFactory,
                       ReserveTicketsService service) throws InterruptedException {

        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger acceptedQuantity = new AtomicInteger();
        Map<ErrorCode, Integer> rejections = new ConcurrentHashMap<>();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        CyclicBarrier startLine = new CyclicBarrier(attempts);
        CountDownLatch finished = new CountDownLatch(attempts);

        Instant start;
        // Virtual threads, not a bounded pool. A CyclicBarrier of N parties needs all N
        // threads running simultaneously; with a fixed pool smaller than `attempts` the
        // surplus tasks sit in the queue, the barrier never trips, and the run deadlocks
        // until the await() timeout. Virtual threads make "one thread per attempt" free, so
        // the barrier semantics are honest at any scale.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            start = Instant.now();
            for (int i = 0; i < attempts; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        ReserveTicketsCommand command = commandFactory.apply(index);
                        startLine.await(30, TimeUnit.SECONDS);
                        var reservation = service.reserve(command);
                        accepted.incrementAndGet();
                        acceptedQuantity.addAndGet(reservation.getQty());
                    } catch (DomainException rejection) {
                        rejections.merge(rejection.errorCode(), 1, Integer::sum);
                    } catch (Throwable failure) {
                        unexpected.add(failure);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            if (!finished.await(3, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Concurrent reserve run did not finish in time");
            }
        }

        return new Outcome(accepted.get(), acceptedQuantity.get(), Map.copyOf(rejections),
                List.copyOf(unexpected), Duration.between(start, Instant.now()));
    }
}
