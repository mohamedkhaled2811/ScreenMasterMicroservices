package com.gr74.payment.exception;

/**
 * Thrown when a charge is looked up by an identifier that has no row. Maps to
 * {@link PaymentErrorCode#PAYMENT_NOT_FOUND} (HTTP 404) via {@link GlobalExceptionHandler}.
 */
public class PaymentNotFoundException extends PaymentException {

    public PaymentNotFoundException(String message) {
        super(PaymentErrorCode.PAYMENT_NOT_FOUND, message);
    }
}
