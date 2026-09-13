package com.gr74.notification.exception;

/**
 * Base class for every error this service raises on purpose.
 *
 * <p>Carrying a {@link NotificationErrorCode} on the exception is what would let a future
 * {@code @RestControllerAdvice} translate a thrown exception into the right HTTP status <em>and</em>
 * the stable {@code code} a caller branches on — without a chain of {@code instanceof} checks. There
 * is no advice today (this consumer-only service has no controllers), so nothing throws it either;
 * it exists as the convention's placeholder, mirror of {@code payment/exception/PaymentException}.
 * Throw a subclass (or this class directly) from the service layer; never let a raw
 * {@link RuntimeException} escape with no code.
 */
public class NotificationException extends RuntimeException {

    private final transient NotificationErrorCode errorCode;

    public NotificationException(NotificationErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public NotificationException(NotificationErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public NotificationErrorCode errorCode() {
        return errorCode;
    }
}