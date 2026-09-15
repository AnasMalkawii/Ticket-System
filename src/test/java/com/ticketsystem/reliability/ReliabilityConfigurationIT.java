package com.ticketsystem.reliability;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketsystem.reservation.application.InventoryReservationStrategy;
import com.ticketsystem.schema.AbstractPostgresIT;
import com.ticketsystem.shared.config.TicketingProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/** Proves the Day 8 timeout and shutdown values reach the running application. */
class ReliabilityConfigurationIT extends AbstractPostgresIT {

    @Autowired
    private Environment environment;

    @Autowired
    private TicketingProperties ticketingProperties;

    @Test
    @DisplayName("database statements and row locks have server-side deadlines")
    void postgresSessionsReceiveBoundedTimeouts() {
        assertThat(jdbc().queryForObject("SHOW statement_timeout", String.class)).isEqualTo("3s");
        assertThat(jdbc().queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
    }

    @Test
    @DisplayName("HTTP, scheduler, and application shutdown are explicitly bounded")
    void lifecycleAndHttpDeadlinesAreConfigured() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("server.tomcat.connection-timeout"))
                .isEqualTo("2s");
        assertThat(environment.getProperty("server.tomcat.max-connections", Integer.class))
                .isEqualTo(4096);
        assertThat(environment.getProperty("server.tomcat.accept-count", Integer.class))
                .isEqualTo(2048);
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase"))
                .isEqualTo("20s");
        assertThat(environment.getProperty("spring.transaction.default-timeout"))
                .isEqualTo("5s");
        assertThat(environment.getProperty(
                "spring.task.scheduling.shutdown.await-termination", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.rabbitmq.listener.simple.force-stop",
                Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("Day 9 uses a bounded pool and the measured atomic inventory strategy")
    void scaleDefaultsAreConservativeAndExplicit() {
        assertThat(environment.getProperty(
                "spring.datasource.hikari.maximum-pool-size", Integer.class)).isEqualTo(8);
        assertThat(environment.getProperty(
                "spring.datasource.hikari.minimum-idle", Integer.class)).isEqualTo(2);
        assertThat(ticketingProperties.reservation().inventoryStrategy())
                .isEqualTo(InventoryReservationStrategy.ATOMIC);
    }

    @Test
    @DisplayName("Day 10 exposes Prometheus histograms and structured correlation-ready logs")
    void observabilityDefaultsAreExplicit() {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .contains("prometheus");
        assertThat(environment.getProperty("management.metrics.distribution.percentiles-histogram.http.server.requests",
                Boolean.class)).isTrue();
        assertThat(environment.getProperty("management.metrics.distribution.percentiles-histogram.ticketing.reservation.duration",
                Boolean.class)).isTrue();
        assertThat(environment.getProperty("logging.structured.format.console"))
                .isEqualTo("logstash");
    }
}
