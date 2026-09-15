package com.ticketsystem.reservation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.schema.AbstractPostgresIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Proves the production timer is parsed and registered; worker behavior is tested separately. */
@SpringBootTest(properties = {
        "ticketing.expiry-worker.scheduling-enabled=true",
        "ticketing.expiry-worker.interval=1h"
})
class ReservationExpirySchedulerIT extends AbstractPostgresIT {

    @Autowired
    private ReservationExpiryScheduler scheduler;

    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduledTasks;

    @Test
    @DisplayName("the production expiry schedule is enabled and registered")
    void scheduleIsRegistered() {
        assertThat(scheduler).isNotNull();
        assertThat(scheduledTasks.getScheduledTasks()).isNotEmpty();
    }
}
