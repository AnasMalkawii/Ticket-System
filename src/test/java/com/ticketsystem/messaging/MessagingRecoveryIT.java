package com.ticketsystem.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ticketsystem.messaging.config.RabbitTopology;
import com.ticketsystem.messaging.domain.OutboxEvent;
import com.ticketsystem.messaging.publisher.OutboxPublisherWorker;
import com.ticketsystem.messaging.publisher.RabbitOutboxPublisher;
import com.ticketsystem.messaging.repository.OutboxEventRepository;
import com.ticketsystem.order.application.ConfirmReservationService;
import com.ticketsystem.reservation.application.RequestFingerprint;
import com.ticketsystem.reservation.application.ReserveTicketsCommand;
import com.ticketsystem.reservation.application.ReserveTicketsService;
import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Day 7 proof: RabbitMQ is outside the booking transaction, publisher confirms control the
 * outbox marker, duplicate delivery is logical once, and poison messages reach the DLQ.
 */
@SpringBootTest(properties = {
        "ticketing.messaging.enabled=true",
        "ticketing.messaging.publisher-scheduling-enabled=false",
        "ticketing.messaging.confirm-timeout=1s",
        "ticketing.messaging.initial-retry-delay=100ms",
        "ticketing.messaging.max-retry-delay=1s",
        "spring.rabbitmq.connection-timeout=1s",
        "spring.rabbitmq.listener.simple.concurrency=1",
        "spring.rabbitmq.listener.simple.max-concurrency=1"
})
class MessagingRecoveryIT extends AbstractPostgresIT {

    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3-management"));

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private ReserveTicketsService reserveTicketsService;

    @Autowired
    private ConfirmReservationService confirmReservationService;

    @Autowired
    private OutboxPublisherWorker publisherWorker;

    @Autowired
    private RabbitOutboxPublisher rabbitPublisher;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;

    @BeforeEach
    void prepareBroker() throws Exception {
        ensureBrokerRunning();
        startListeners();
        purgeQueues();
    }

    @AfterEach
    void restoreBroker() throws Exception {
        ensureBrokerRunning();
        startListeners();
        purgeQueues();
    }

    @Test
    @DisplayName("broker outage cannot roll back confirmation and the event drains after recovery")
    void brokerOutageDuringConfirmationRecoversWithoutCorruptingBooking() throws Exception {
        Reservation reservation = reserve(2);
        stopBrokerApplication();

        var confirmation = confirmReservationService.confirm(
                reservation.getId(), reservation.getUserId(), "tok_ok", "day7-broker-down");

        assertThat(confirmation.reservation().getStatus().name()).isEqualTo("CONFIRMED");
        assertBookingState(reservation.getId(), 98, 0, 2);
        UUID confirmedEventId = confirmedOutboxEventId(reservation.getId());
        assertOutboxUnpublished(confirmedEventId);

        // A real publish attempt while RabbitMQ is down must record retry state, not mark
        // either event published and not affect the already committed booking.
        assertThat(publisherWorker.publishBatch().failed()).isEqualTo(1);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class))
                .isEqualTo(2);
        assertBookingState(reservation.getId(), 98, 0, 2);

        startBrokerApplication();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    publisherWorker.publishBatch();
                    assertThat(outboxRepository.countByPublishedAtIsNull()).isZero();
                });
        awaitProcessedOnce(confirmedEventId);

        assertThat(jdbc().queryForObject(
                "SELECT published_at IS NOT NULL FROM outbox_event WHERE id = ?::uuid",
                Boolean.class, confirmedEventId.toString())).isTrue();
        assertBookingState(reservation.getId(), 98, 0, 2);
    }

    @Test
    @DisplayName("two deliveries of one event invoke one logical notification")
    void duplicateDeliveryIsIdempotent() {
        OutboxEvent confirmed = createConfirmedEvent();
        stopListeners();

        rabbitPublisher.publish(confirmed);
        rabbitPublisher.publish(confirmed);
        assertThat(queueMessageCount(RabbitTopology.NOTIFICATION_QUEUE)).isEqualTo(2);

        startListeners();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(queueMessageCount(RabbitTopology.NOTIFICATION_QUEUE)).isZero();
            assertProcessedOnce(confirmed.getId());
        });
    }

    @Test
    @DisplayName("poison message is retried a bounded number of times and routed to the DLQ")
    void poisonMessageReachesDeadLetterQueue() {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message poison = new Message("{}".getBytes(StandardCharsets.UTF_8), properties);

        rabbitTemplate.send("", RabbitTopology.NOTIFICATION_QUEUE, poison);

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(queueMessageCount(RabbitTopology.DEAD_LETTER_QUEUE)).isEqualTo(1));
        Message deadLetter = rabbitTemplate.receive(RabbitTopology.DEAD_LETTER_QUEUE, 2000);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    private OutboxEvent createConfirmedEvent() {
        Reservation reservation = reserve(1);
        confirmReservationService.confirm(
                reservation.getId(), reservation.getUserId(), "tok_ok", "day7-duplicate");
        UUID eventId = confirmedOutboxEventId(reservation.getId());
        return outboxRepository.findById(eventId).orElseThrow();
    }

    private Reservation reserve(int quantity) {
        UUID userId = UUID.randomUUID();
        ReserveTicketsCommand command = new ReserveTicketsCommand(
                UUID.fromString(HOT_EVENT), userId, quantity,
                "day7-" + UUID.randomUUID(),
                RequestFingerprint.of(UUID.fromString(HOT_EVENT), userId, quantity),
                "day7-test");
        return reserveTicketsService.reserve(command);
    }

    private UUID confirmedOutboxEventId(UUID reservationId) {
        return jdbc().queryForObject("""
                SELECT id FROM outbox_event
                WHERE aggregate_id = ?::uuid AND type = 'reservation.confirmed'
                """, UUID.class, reservationId.toString());
    }

    private void assertOutboxUnpublished(UUID eventId) {
        Map<String, Object> row = jdbc().queryForMap("""
                SELECT attempts, published_at, next_attempt_at
                FROM outbox_event WHERE id = ?::uuid
                """, eventId.toString());
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("next_attempt_at")).isNotNull();
    }

    private void assertBookingState(UUID reservationId, int available, int held, int sold) {
        Map<String, Object> state = jdbc().queryForMap("""
                SELECT r.status, i.available, i.held, i.sold,
                       v.conservation_drift, v.held_drift, v.sold_drift,
                       (SELECT count(*) FROM ticket_order o WHERE o.reservation_id = r.id) orders
                FROM reservation r
                JOIN ticket_inventory i ON i.event_id = r.event_id
                JOIN v_inventory_reconciliation v ON v.event_id = r.event_id
                WHERE r.id = ?::uuid
                """, reservationId.toString());
        assertThat(state.get("status")).isEqualTo("CONFIRMED");
        assertThat(state.get("available")).isEqualTo(available);
        assertThat(state.get("held")).isEqualTo(held);
        assertThat(state.get("sold")).isEqualTo(sold);
        assertThat(((Number) state.get("orders")).longValue()).isOne();
        assertThat(((Number) state.get("conservation_drift")).longValue()).isZero();
        assertThat(((Number) state.get("held_drift")).longValue()).isZero();
        assertThat(((Number) state.get("sold_drift")).longValue()).isZero();
    }

    private void awaitProcessedOnce(UUID eventId) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertProcessedOnce(eventId));
    }

    private void assertProcessedOnce(UUID eventId) {
        assertThat(jdbc().queryForObject("""
                SELECT count(*) FROM processed_event
                WHERE event_id = ?::uuid AND consumer = 'notification-mock'
                """, Integer.class, eventId.toString())).isOne();
    }

    private int queueMessageCount(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        assertThat(information).as("queue %s is declared", queueName).isNotNull();
        return Math.toIntExact(information.getMessageCount());
    }

    private void purgeQueues() {
        for (String queue : new String[] {
                RabbitTopology.RESERVATION_CONFIRMED_QUEUE,
                RabbitTopology.NOTIFICATION_QUEUE,
                RabbitTopology.ANALYTICS_AUDIT_QUEUE,
                RabbitTopology.DEAD_LETTER_QUEUE}) {
            rabbitAdmin.purgeQueue(queue, true);
        }
    }

    private void stopListeners() {
        listenerRegistry.getListenerContainers().forEach(container -> container.stop());
    }

    private void startListeners() {
        listenerRegistry.getListenerContainers().forEach(container -> {
            if (!container.isRunning()) {
                container.start();
            }
        });
    }

    private static void stopBrokerApplication() throws Exception {
        Container.ExecResult result = RABBIT.execInContainer("rabbitmqctl", "stop_app");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }

    private static void startBrokerApplication() throws Exception {
        Container.ExecResult result = RABBIT.execInContainer("rabbitmqctl", "start_app");
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        await().atMost(30, TimeUnit.SECONDS).until(MessagingRecoveryIT::brokerResponds);
    }

    private static void ensureBrokerRunning() throws Exception {
        if (!brokerResponds()) {
            startBrokerApplication();
        }
    }

    private static boolean brokerResponds() {
        try {
            return RABBIT.execInContainer("rabbitmq-diagnostics", "-q", "ping")
                    .getExitCode() == 0;
        } catch (Exception failure) {
            return false;
        }
    }
}
