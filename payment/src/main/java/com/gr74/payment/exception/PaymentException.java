package com.gr74.payment.exception;

/**
 * Base class for deliberate service errors; carries the {@link PaymentErrorCode} to render.
 */
public class PaymentException extends RuntimeException {

    private final transient PaymentErrorCode errorCode;

    public PaymentException(PaymentErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PaymentException(PaymentErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public PaymentErrorCode errorCode() {
        return errorCode;
    }
}
