package com.ticketsystem.messaging.consumer;

import com.ticketsystem.messaging.repository.ProcessedEventRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One deliberately small idempotent consumer side effect backed by processed_event. */
@Service
@ConditionalOnProperty(prefix = "ticketing.messaging", name = "enabled", havingValue = "true")
public class NotificationMockHandler {

    public static final String CONSUMER_NAME = "notification-mock";
    private static final Logger log = LoggerFactory.getLogger(NotificationMockHandler.class);

    private final ProcessedEventRepository processedEvents;

    public NotificationMockHandler(ProcessedEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    @Transactional
    public boolean handle(UUID eventId) {
        boolean firstDelivery = processedEvents.recordIfFirst(eventId, CONSUMER_NAME) == 1;
        if (firstDelivery) {
            log.info("Notification mock processed reservation confirmation [eventId={}]", eventId);
        } else {
            log.info("Notification mock ignored duplicate delivery [eventId={}]", eventId);
        }
        return firstDelivery;
    }
}
