package com.gr74.notification.exception;

/**
 * Base exception carrying a {@link NotificationErrorCode}.
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