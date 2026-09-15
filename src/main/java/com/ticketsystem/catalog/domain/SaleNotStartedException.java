package com.ticketsystem.catalog.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.time.Instant;
import java.util.UUID;

public class SaleNotStartedException extends DomainException {

    private final Instant saleStartsAt;

    public SaleNotStartedException(UUID eventId, Instant saleStartsAt) {
        super(ErrorCode.SALE_NOT_STARTED,
                "Sale for event %s opens at %s".formatted(eventId, saleStartsAt));
        this.saleStartsAt = saleStartsAt;
    }

    public Instant getSaleStartsAt() {
        return saleStartsAt;
    }
}
