package com.ticketsystem.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Exhaustive test of the transition table. This runs without a database or a Spring context,
 * so the rules can be checked in milliseconds on every build.
 */
class ReservationStatusTest {

    @Test
    @DisplayName("PENDING is the only state with outgoing transitions")
    void pendingIsTheOnlyActiveState() {
        assertThat(ReservationStatus.PENDING.allowedTransitions())
                .containsExactlyInAnyOrder(ReservationStatus.CONFIRMED,
                        ReservationStatus.CANCELLED, ReservationStatus.EXPIRED);
        assertThat(ReservationStatus.PENDING.isTerminal()).isFalse();
        assertThat(ReservationStatus.PENDING.isActive()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class,
            names = {"CONFIRMED", "CANCELLED", "EXPIRED"})
    @DisplayName("terminal states have no outgoing transitions at all")
    void terminalStatesAreFinal(ReservationStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.isActive()).isFalse();
        assertThat(terminal.allowedTransitions()).isEmpty();

        for (ReservationStatus target : ReservationStatus.values()) {
            assertThat(terminal.canTransitionTo(target))
                    .as("%s -> %s must be rejected", terminal, target)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("the full transition matrix permits exactly three edges")
    void transitionMatrixIsExhaustivelyDefined() {
        int legalEdges = 0;
        for (ReservationStatus from : ReservationStatus.values()) {
            for (ReservationStatus to : ReservationStatus.values()) {
                if (from.canTransitionTo(to)) {
                    legalEdges++;
                    assertThat(from).isEqualTo(ReservationStatus.PENDING);
                }
            }
        }
        assertThat(legalEdges).isEqualTo(3);
    }

    @Test
    @DisplayName("no state may transition to itself")
    void selfTransitionsAreRejected() {
        for (ReservationStatus status : ReservationStatus.values()) {
            assertThat(status.canTransitionTo(status))
                    .as("%s -> %s", status, status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("PENDING and CONFIRMED consume a user's per-event cap (I6)")
    void statesCountingTowardUserLimit() {
        Set<ReservationStatus> counting = EnumSet.noneOf(ReservationStatus.class);
        for (ReservationStatus status : ReservationStatus.values()) {
            if (status.countsTowardUserLimit()) {
                counting.add(status);
            }
        }
        assertThat(counting)
                .containsExactlyInAnyOrder(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("only CANCELLED and EXPIRED return tickets to the pool")
    void statesReleasingInventory() {
        assertThat(ReservationStatus.CANCELLED.releasesInventory()).isTrue();
        assertThat(ReservationStatus.EXPIRED.releasesInventory()).isTrue();
        assertThat(ReservationStatus.CONFIRMED.releasesInventory()).isFalse();
        assertThat(ReservationStatus.PENDING.releasesInventory()).isFalse();
    }

    @Test
    @DisplayName("allowedTransitions() is unmodifiable")
    void transitionSetsAreImmutable() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> ReservationStatus.PENDING.allowedTransitions()
                        .add(ReservationStatus.PENDING))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
