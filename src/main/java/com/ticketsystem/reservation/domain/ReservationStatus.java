package com.ticketsystem.reservation.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Reservation lifecycle states and the complete set of legal transitions between them.
 *
 * <p>{@link #PENDING} is the only non-terminal state. {@link #CONFIRMED}, {@link #CANCELLED}
 * and {@link #EXPIRED} are terminal and have no outgoing edges, which is what makes
 * cancel-versus-expire races (F-06) and retried confirmations (F-07) safe: whichever
 * transaction commits first wins, and the loser observes a terminal state and refuses.
 *
 * <p>The transition table lives here rather than being scattered across services, so the
 * rules can be unit-tested exhaustively without a database.
 */
public enum ReservationStatus {

    PENDING,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED_TRANSITIONS =
            Map.of(
                    PENDING, Collections.unmodifiableSet(EnumSet.of(CONFIRMED, CANCELLED, EXPIRED)),
                    CONFIRMED, Collections.unmodifiableSet(EnumSet.noneOf(ReservationStatus.class)),
                    CANCELLED, Collections.unmodifiableSet(EnumSet.noneOf(ReservationStatus.class)),
                    EXPIRED, Collections.unmodifiableSet(EnumSet.noneOf(ReservationStatus.class)));

    /** A terminal state has no legal outgoing transition. Terminal is forever. */
    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    public boolean isActive() {
        return this == PENDING;
    }

    /** Whether this state still counts against a user's per-event cap (I6). */
    public boolean countsTowardUserLimit() {
        return this == PENDING || this == CONFIRMED;
    }

    /** Whether reaching this state returns held tickets to the available pool. */
    public boolean releasesInventory() {
        return this == CANCELLED || this == EXPIRED;
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public Set<ReservationStatus> allowedTransitions() {
        return ALLOWED_TRANSITIONS.get(this);
    }
}
