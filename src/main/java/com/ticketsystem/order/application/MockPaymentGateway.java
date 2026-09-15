package com.ticketsystem.order.application;

import com.ticketsystem.order.domain.PaymentDeclinedException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Local deterministic payment boundary used by v1: tok_ok succeeds and every other token
 * declines. It is deliberately invoked before the inventory lock is acquired.
 */
@Component
class MockPaymentGateway implements PaymentGateway {

    private final PaymentProperties properties;

    MockPaymentGateway(PaymentProperties properties) {
        this.properties = properties;
    }

    @Override
    public PaymentAuthorization authorize(UUID reservationId, long amountMinor,
                                          String currency, String paymentToken) {
        if ("tok_timeout".equals(paymentToken)) {
            try {
                Thread.sleep(properties.mockTimeoutDelay());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Simulated payment call was cancelled", interrupted);
            }
        }
        if (!"tok_ok".equals(paymentToken)) {
            throw new PaymentDeclinedException();
        }
        return new PaymentAuthorization("mock:" + reservationId);
    }
}
