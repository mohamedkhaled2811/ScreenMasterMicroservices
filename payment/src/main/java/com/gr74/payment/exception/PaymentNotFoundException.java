package com.gr74.payment.exception;

/**
 * No payment exists for the requested identifier (404).
 */
public class PaymentNotFoundException extends PaymentException {

    public PaymentNotFoundException(String message) {
        super(PaymentErrorCode.PAYMENT_NOT_FOUND, message);
    }
}
