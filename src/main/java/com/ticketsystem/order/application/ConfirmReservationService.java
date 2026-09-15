package com.ticketsystem.order.application;

import com.ticketsystem.observability.TicketingMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Coordinates payment outside the inventory lock, then commits confirmation atomically. */
@Service
public class ConfirmReservationService {

    private final ConfirmationQuoteReader quoteReader;
    private final BoundedPaymentGateway paymentGateway;
    private final ConfirmReservationTransaction transaction;
    private final TicketingMetrics metrics;

    public ConfirmReservationService(ConfirmationQuoteReader quoteReader,
                                     BoundedPaymentGateway paymentGateway,
                                     ConfirmReservationTransaction transaction,
                                     TicketingMetrics metrics) {
        this.quoteReader = quoteReader;
        this.paymentGateway = paymentGateway;
        this.transaction = transaction;
        this.metrics = metrics;
    }

    public ConfirmationResult confirm(UUID reservationId, UUID actorUserId,
                                      String paymentToken, String correlationId) {
        Timer.Sample sample = metrics.startConfirmation();
        try {
            ConfirmationQuote quote = quoteReader.quote(reservationId, actorUserId);
            PaymentAuthorization payment = paymentGateway.authorize(
                    quote.reservationId(), quote.amountMinor(), quote.currency(), paymentToken);
            ConfirmationResult result = transaction.confirm(
                    quote, payment, actorUserId, correlationId);
            metrics.confirmationSucceeded();
            return result;
        } catch (RuntimeException failure) {
            metrics.confirmationFailed(failure);
            throw failure;
        } finally {
            metrics.stopConfirmation(sample);
        }
    }
}
