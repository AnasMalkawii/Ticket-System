package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.schema.AbstractPostgresIT;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Keeps the original Day 3 branch executable for repeatable Day 9 comparisons. */
@TestPropertySource(properties = "ticketing.reservation.inventory-strategy=PESSIMISTIC")
class PessimisticStrategyIT extends AbstractPostgresIT {

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Test
    @DisplayName("pessimistic comparison branch still prevents overselling")
    void pessimisticBranchRemainsCorrect() throws Exception {
        jdbc().update("""
                UPDATE ticket_inventory
                   SET total = 20, available = 20, held = 0, sold = 0, version = 0
                 WHERE event_id = ?::uuid
                """, HOT_EVENT);
        UUID eventId = UUID.fromString(HOT_EVENT);

        var outcome = ConcurrentReserveHarness.run(60, index -> {
            UUID userId = UUID.randomUUID();
            return new ReserveTicketsCommand(eventId, userId, 1,
                    "pessimistic-" + UUID.randomUUID(),
                    RequestFingerprint.of(eventId, userId, 1), null);
        }, reserveTicketsService);

        assertThat(outcome.unexpectedFailures()).isEmpty();
        assertThat(outcome.accepted()).isEqualTo(20);
        assertThat(outcome.rejected(ErrorCode.SOLD_OUT)).isEqualTo(40);
        assertThat(jdbc().queryForObject("""
                SELECT count(*) FROM v_inventory_reconciliation
                 WHERE conservation_drift <> 0 OR held_drift <> 0 OR sold_drift <> 0
                """, Integer.class)).isZero();
    }
}
