package com.ticketsystem.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Counter movement rules. Invariants I1 and I2 must hold after every operation. */
class TicketInventoryTest {

    private static final UUID EVENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant T0 = Instant.parse("2026-08-23T10:00:00Z");

    private static TicketInventory hundredTickets() {
        return TicketInventory.of(EVENT, 100, T0);
    }

    @Test
    @DisplayName("a new inventory has everything available and nothing held or sold")
    void creation() {
        TicketInventory inventory = hundredTickets();

        assertThat(inventory.getTotal()).isEqualTo(100);
        assertThat(inventory.getAvailable()).isEqualTo(100);
        assertThat(inventory.getHeld()).isZero();
        assertThat(inventory.getSold()).isZero();
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("hold moves tickets from available to held and conserves the total")
    void holdMovesAvailableToHeld() {
        TicketInventory inventory = hundredTickets();

        inventory.hold(3, T0);

        assertThat(inventory.getAvailable()).isEqualTo(97);
        assertThat(inventory.getHeld()).isEqualTo(3);
        assertThat(inventory.getSold()).isZero();
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("confirm moves held to sold; release moves held back to available")
    void confirmAndRelease() {
        TicketInventory inventory = hundredTickets();
        inventory.hold(5, T0);

        inventory.confirmHold(2, T0);
        inventory.releaseHold(3, T0);

        assertThat(inventory.getAvailable()).isEqualTo(98);
        assertThat(inventory.getHeld()).isZero();
        assertThat(inventory.getSold()).isEqualTo(2);
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("holding more than remains is rejected as SOLD_OUT, never as a negative counter (I1)")
    void cannotOversell() {
        TicketInventory inventory = TicketInventory.of(EVENT, 100, T0); // hundredTickets()
        inventory.hold(98, T0);

        assertThatThrownBy(() -> inventory.hold(3, T0))
                .isInstanceOf(InsufficientInventoryException.class)
                .hasMessageContaining("2 tickets available");

        assertThat(inventory.getAvailable())
                .as("a rejected hold must not change any counter")
                .isEqualTo(2);
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("the last ticket can be held, and the next attempt is rejected")
    void exactlyExhaustsInventory() {
        TicketInventory inventory = TicketInventory.of(EVENT, 1, T0);

        inventory.hold(1, T0);
        assertThat(inventory.isSoldOut()).isTrue();

        assertThatThrownBy(() -> inventory.hold(1, T0))
                .isInstanceOf(InsufficientInventoryException.class);
        assertThat(inventory.getAvailable()).isZero();
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("releasing or confirming more than is held is rejected")
    void cannotReleaseMoreThanHeld() {
        TicketInventory inventory = hundredTickets();
        inventory.hold(2, T0);

        assertThatThrownBy(() -> inventory.releaseHold(3, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> inventory.confirmHold(3, T0))
                .isInstanceOf(IllegalStateException.class);

        assertThat(inventory.getHeld()).isEqualTo(2);
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("non-positive quantities are rejected on every operation")
    void rejectsNonPositiveQuantities() {
        TicketInventory inventory = hundredTickets();
        inventory.hold(1, T0);

        assertThatThrownBy(() -> inventory.hold(0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventory.hold(-1, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventory.releaseHold(0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inventory.confirmHold(-2, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketInventory.of(EVENT, 0, T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("adding tickets grows total and available together")
    void addTickets() {
        TicketInventory inventory = hundredTickets();
        inventory.hold(10, T0);

        inventory.addTickets(50, T0);

        assertThat(inventory.getTotal()).isEqualTo(150);
        assertThat(inventory.getAvailable()).isEqualTo(140);
        assertThat(inventory.getHeld()).isEqualTo(10);
        assertThat(inventory.isConserved()).isTrue();
    }

    @Test
    @DisplayName("conservation holds across a long random sequence of legal operations")
    void conservationIsPreservedUnderManyOperations() {
        TicketInventory inventory = TicketInventory.of(EVENT, 100, T0);
        var random = new java.util.Random(42);

        for (int i = 0; i < 5_000; i++) {
            switch (random.nextInt(3)) {
                case 0 -> {
                    int qty = 1 + random.nextInt(4);
                    if (inventory.getAvailable() >= qty) {
                        inventory.hold(qty, T0);
                    }
                }
                case 1 -> {
                    int qty = 1 + random.nextInt(4);
                    if (inventory.getHeld() >= qty) {
                        inventory.releaseHold(qty, T0);
                    }
                }
                default -> {
                    int qty = 1 + random.nextInt(4);
                    if (inventory.getHeld() >= qty) {
                        inventory.confirmHold(qty, T0);
                    }
                }
            }
            assertThat(inventory.isConserved()).as("iteration %d", i).isTrue();
            assertThat(inventory.getAvailable()).isNotNegative();
            assertThat(inventory.getHeld()).isNotNegative();
            assertThat(inventory.getSold()).isNotNegative();
        }
        assertThat(inventory.getTotal()).isEqualTo(100);
    }

    @Test
    @DisplayName("the version counter advances on every successful mutation")
    void versionTracksMutations() {
        TicketInventory inventory = hundredTickets();
        assertThat(inventory.getVersion()).isZero();

        inventory.hold(1, T0);
        inventory.confirmHold(1, T0);

        assertThat(inventory.getVersion()).isEqualTo(2);
    }
}
