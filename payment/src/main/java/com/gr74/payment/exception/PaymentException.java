package com.gr74.payment.exception;

/**
 * Base class for every error this service raises on purpose.
 *
 * <p>Carrying a {@link PaymentErrorCode} on the exception is what lets {@link GlobalExceptionHandler}
 * translate a thrown exception into the right HTTP status <em>and</em> the stable {@code code} the
 * caller branches on — without a chain of {@code instanceof} checks. Throw a subclass (or this class
 * directly) from the service layer; never let a raw {@link RuntimeException} escape with no code, or
 * the caller gets an opaque 500 it can't reason about.
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
