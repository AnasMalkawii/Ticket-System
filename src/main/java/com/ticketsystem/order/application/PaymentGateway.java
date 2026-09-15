package com.ticketsystem.order.application;

import java.util.UUID;

/** Remote-payment port. The coordinator applies the deadline and deliberately does not retry. */
interface PaymentGateway {

    PaymentAuthorization authorize(UUID reservationId, long amountMinor,
                                   String currency, String paymentToken);
}
