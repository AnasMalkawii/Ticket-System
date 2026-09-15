package com.ticketsystem.order.application;

import com.ticketsystem.order.domain.PaymentGatewayTimeoutException;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Applies one end-to-end deadline around payment without blind retries.
 *
 * <p>The call stays outside the reservation/inventory transaction, so a slow provider cannot
 * hold the hot inventory row. A timeout cancels the task and leaves the hold {@code PENDING}.
 */
@Component
class BoundedPaymentGateway {

    private final PaymentGateway delegate;
    private final PaymentProperties properties;
    private final ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();

    BoundedPaymentGateway(PaymentGateway delegate, PaymentProperties properties) {
        this.delegate = delegate;
        this.properties = properties;
    }

    PaymentAuthorization authorize(UUID reservationId, long amountMinor,
                                   String currency, String paymentToken) {
        Future<PaymentAuthorization> call = calls.submit(() -> delegate.authorize(
                reservationId, amountMinor, currency, paymentToken));
        try {
            return call.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            call.cancel(true);
            throw new PaymentGatewayTimeoutException(properties.timeout());
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new PaymentGatewayTimeoutException(properties.timeout());
        } catch (ExecutionException failedCall) {
            if (failedCall.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw new IllegalStateException("Payment gateway failed", failedCall.getCause());
        }
    }

    @PreDestroy
    void stopAcceptingPaymentCalls() {
        calls.shutdownNow();
    }
}
