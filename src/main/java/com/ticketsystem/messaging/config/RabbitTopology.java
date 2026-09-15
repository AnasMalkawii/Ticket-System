package com.ticketsystem.messaging.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Durable Day 7 RabbitMQ exchanges, work queues, bindings, and poison-message DLQ. */
@Configuration(proxyBeanMethods = false)
@EnableRabbit
@ConditionalOnProperty(prefix = "ticketing.messaging", name = "enabled", havingValue = "true")
public class RabbitTopology {

    public static final String EVENTS_EXCHANGE = "ticketing.events";
    public static final String DEAD_LETTER_EXCHANGE = "ticketing.dead-letter";
    public static final String RESERVATION_CONFIRMED_QUEUE = "ticketing.reservation-confirmed";
    public static final String NOTIFICATION_QUEUE = "ticketing.notification-mock";
    public static final String ANALYTICS_AUDIT_QUEUE = "ticketing.analytics-audit";
    public static final String DEAD_LETTER_QUEUE = "ticketing.poison.dlq";
    public static final String DEAD_LETTER_ROUTING_KEY = "poison";

    @Bean
    public Declarables ticketingTopology() {
        TopicExchange events = ExchangeBuilder.topicExchange(EVENTS_EXCHANGE)
                .durable(true)
                .build();
        DirectExchange deadLetters = ExchangeBuilder.directExchange(DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();

        Queue confirmed = workQueue(RESERVATION_CONFIRMED_QUEUE);
        Queue notification = workQueue(NOTIFICATION_QUEUE);
        Queue analytics = workQueue(ANALYTICS_AUDIT_QUEUE);
        Queue deadLetter = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();

        Binding confirmedBinding = BindingBuilder.bind(confirmed)
                .to(events)
                .with("reservation.confirmed");
        Binding notificationBinding = BindingBuilder.bind(notification)
                .to(events)
                .with("reservation.confirmed");
        Binding analyticsBinding = BindingBuilder.bind(analytics)
                .to(events)
                .with("reservation.#");
        Binding deadLetterBinding = BindingBuilder.bind(deadLetter)
                .to(deadLetters)
                .with(DEAD_LETTER_ROUTING_KEY);

        return new Declarables(
                events, deadLetters,
                confirmed, notification, analytics, deadLetter,
                confirmedBinding, notificationBinding, analyticsBinding, deadLetterBinding);
    }

    private static Queue workQueue(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }
}
