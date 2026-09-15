package com.ticketsystem.catalog.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

public class EventNotFoundException extends DomainException {

    public EventNotFoundException(UUID eventId) {
        super(ErrorCode.EVENT_NOT_FOUND, "Event %s does not exist".formatted(eventId));
    }
}
