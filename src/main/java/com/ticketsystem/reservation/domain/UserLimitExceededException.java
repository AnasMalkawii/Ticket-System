package com.ticketsystem.reservation.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

/**
 * The per-user, per-event cap (I6) would be exceeded.
 *
 * <p>Reported separately from {@code SOLD_OUT} on purpose: tickets may well be available,
 * and telling the user "sold out" when the real answer is "you already have four" would be
 * a lie that also hides a real availability signal.
 */
public class UserLimitExceededException extends DomainException {

    private final int alreadyHeld;
    private final int cap;

    public UserLimitExceededException(UUID userId, UUID eventId, int alreadyHeld, int requested,
                                      int cap) {
        super(ErrorCode.USER_LIMIT_EXCEEDED,
                "User %s may hold at most %d tickets for event %s; already holds %d and requested %d"
                        .formatted(userId, cap, eventId, alreadyHeld, requested));
        this.alreadyHeld = alreadyHeld;
        this.cap = cap;
    }

    public int getAlreadyHeld() {
        return alreadyHeld;
    }

    public int getCap() {
        return cap;
    }
}
