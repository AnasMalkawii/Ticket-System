package com.ticketsystem.order.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;
import java.time.Duration;

/** Bounded payment failure; no order or inventory transition has been attempted. */
public class PaymentGatewayTimeoutException extends DomainException {

    public PaymentGatewayTimeoutException(Duration timeout) {
        super(ErrorCode.DEPENDENCY_TIMEOUT,
                "The payment gateway did not respond within %d ms."
                        .formatted(timeout.toMillis()));
    }
}
