package com.ticketsystem.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Lifecycle rules of a hold, exercised without a database. */
class ReservationTest {

    private static final UUID EVENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final Instant T0 = Instant.parse("2026-08-23T10:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(3);
    private static final String FINGERPRINT = "a".repeat(64);

    private static Reservation newHold() {
        return Reservation.pending(EVENT, USER, 2, "key-" + UUID.randomUUID(), FINGERPRINT, TTL, T0);
    }

    @Test
    @DisplayName("a new hold is PENDING with a deadline of now + ttl and no termination time")
    void creation() {
        Reservation hold = newHold();

        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(hold.getExpiresAt()).isEqualTo(T0.plus(TTL));
        assertThat(hold.getTerminatedAt()).isNull();
        assertThat(hold.isActive()).isTrue();
        assertThat(hold.isExpiredAt(T0)).isFalse();
    }

    @Test
    @DisplayName("creation rejects non-positive quantity, blank key, and non-positive ttl")
    void creationValidation() {
        assertThatThrownBy(() -> Reservation.pending(EVENT, USER, 0, "k", FINGERPRINT, TTL, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reservation.pending(EVENT, USER, -1, "k", FINGERPRINT, TTL, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reservation.pending(EVENT, USER, 1, " ", FINGERPRINT, TTL, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reservation.pending(EVENT, USER, 1, "k", FINGERPRINT, Duration.ZERO, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("legal transitions")
    class LegalTransitions {

        @Test
        void confirmBeforeDeadline() {
            Reservation hold = newHold();
            Instant now = T0.plusSeconds(60);

            hold.confirm(now);

            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(hold.getTerminatedAt()).isEqualTo(now);
            assertThat(hold.isActive()).isFalse();
        }

        @Test
        void cancelBeforeDeadline() {
            Reservation hold = newHold();
            Instant now = T0.plusSeconds(30);

            hold.cancel(now);

            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(hold.getTerminatedAt()).isEqualTo(now);
        }

        @Test
        void expireAfterDeadline() {
            Reservation hold = newHold();
            Instant now = T0.plus(TTL);

            hold.expire(now);

            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(hold.getTerminatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("confirming after the deadline is RESERVATION_EXPIRED, not INVALID_STATE")
        void confirmAfterDeadline() {
            Reservation hold = newHold();

            assertThatThrownBy(() -> hold.confirm(T0.plus(TTL)))
                    .isInstanceOf(ReservationExpiredException.class);
            assertThat(hold.getStatus())
                    .as("a rejected transition must leave state untouched")
                    .isEqualTo(ReservationStatus.PENDING);
        }

        @Test
        @DisplayName("the deadline is inclusive: expiresAt itself is already expired")
        void deadlineIsInclusive() {
            Reservation hold = newHold();

            assertThat(hold.isExpiredAt(T0.plus(TTL).minusMillis(1))).isFalse();
            assertThat(hold.isExpiredAt(T0.plus(TTL))).isTrue();
        }

        @Test
        @DisplayName("expiring a live hold is rejected, so a bad worker query cannot free tickets")
        void expireBeforeDeadline() {
            Reservation hold = newHold();

            assertThatThrownBy(() -> hold.expire(T0.plusSeconds(1)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("terminal states reject every further transition (F-06, F-07)")
    class TerminalStates {

        @Test
        void confirmedIsFinal() {
            Reservation hold = newHold();
            hold.confirm(T0.plusSeconds(10));

            assertThatThrownBy(() -> hold.cancel(T0.plusSeconds(20)))
                    .isInstanceOf(IllegalReservationTransitionException.class);
            assertThatThrownBy(() -> hold.confirm(T0.plusSeconds(20)))
                    .isInstanceOf(IllegalReservationTransitionException.class);
            assertThatThrownBy(() -> hold.expire(T0.plus(TTL)))
                    .isInstanceOf(IllegalReservationTransitionException.class);
            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("cancel loses to expiry and cannot release inventory a second time")
        void cancelAfterExpiry() {
            Reservation hold = newHold();
            hold.expire(T0.plus(TTL));

            assertThatThrownBy(() -> hold.cancel(T0.plus(TTL).plusSeconds(1)))
                    .isInstanceOf(IllegalReservationTransitionException.class);
            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(hold.getTerminatedAt()).isEqualTo(T0.plus(TTL));
        }

        @Test
        @DisplayName("expiry loses to cancel and cannot release inventory a second time")
        void expireAfterCancel() {
            Reservation hold = newHold();
            hold.cancel(T0.plusSeconds(5));

            assertThatThrownBy(() -> hold.expire(T0.plus(TTL)))
                    .isInstanceOf(IllegalReservationTransitionException.class);
            assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(hold.getTerminatedAt()).isEqualTo(T0.plusSeconds(5));
        }
    }

    @Test
    @DisplayName("no public setter exposes status or terminatedAt")
    void statusHasNoSetter() {
        var setters = Arrays.stream(Reservation.class.getMethods())
                .map(Method::getName)
                .filter(name -> name.startsWith("set"))
                .toList();

        assertThat(setters)
                .as("transitions must go through confirm/cancel/expire only")
                .isEmpty();
    }
}
