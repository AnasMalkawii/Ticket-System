package com.ticketsystem.shared.time;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Supplies the current transaction's database timestamp.
 *
 * <p>Every time-sensitive decision - is the sale open, has this hold expired - uses this
 * rather than {@code Instant.now()}. With several replicas, a skewed container clock would
 * otherwise open a sale early on one instance or expire live holds on another (F-18). One
 * clock, owned by the database, removes the whole class of problem.
 *
 * <p>PostgreSQL's {@code now()} returns the transaction start time, so it is stable for the
 * life of the transaction and callers can fetch it once and pass it down.
 *
 * <p>Cost: one round trip. It is deliberately taken <em>before</em> the inventory row lock is
 * acquired, so it adds latency to the individual request but no time to the critical section
 * that every other reserver is queued behind. If Day 9 measurements show it matters, it can
 * be folded into the event lookup as an extra selected column.
 */
@Component
public class DatabaseTimeProvider {

    private final EntityManager entityManager;

    public DatabaseTimeProvider(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Instant now() {
        // createNativeQuery(String, Class) returns an untyped Query in JPA, hence the cast.
        return (Instant) entityManager.createNativeQuery("SELECT now()", Instant.class)
                .getSingleResult();
    }
}
