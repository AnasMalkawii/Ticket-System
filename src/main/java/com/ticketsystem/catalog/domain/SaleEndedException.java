package com.ticketsystem.catalog.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.util.UUID;

public class SaleEndedException extends DomainException {

    public SaleEndedException(UUID eventId) {
        super(ErrorCode.SALE_ENDED, "Sale for event %s is closed".formatted(eventId));
    }
}
