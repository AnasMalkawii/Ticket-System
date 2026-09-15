package com.ticketsystem.order.application;

import java.util.UUID;

/** Immutable payment instruction read before the locked confirmation transaction. */
record ConfirmationQuote(UUID reservationId, long amountMinor, String currency) {
}
