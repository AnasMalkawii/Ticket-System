package com.ticketsystem.catalog.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Invalid catalog query or patch that must be reported as a stable 400 response. */
public class InvalidCatalogRequestException extends DomainException {

    public InvalidCatalogRequestException(String detail) {
        super(ErrorCode.VALIDATION_ERROR, detail);
    }
}
