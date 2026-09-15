package com.ticketsystem.order.domain;

import com.ticketsystem.shared.error.DomainException;
import com.ticketsystem.shared.error.ErrorCode;

/** Deterministic mock-gateway decline; the reservation remains pending. */
public class PaymentDeclinedException extends DomainException {

    public PaymentDeclinedException() {
        super(ErrorCode.PAYMENT_DECLINED, "The mock payment gateway declined this payment.");
    }
}
