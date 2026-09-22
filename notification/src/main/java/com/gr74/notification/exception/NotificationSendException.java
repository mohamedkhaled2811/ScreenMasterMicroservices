package com.gr74.notification.exception;

/**
 * Delivery failed. Must propagate out of the listener so the claim rolls back and the broker redelivers.
 */
public class NotificationSendException extends NotificationException {

    public NotificationSendException(String message, Throwable cause) {
        super(NotificationErrorCode.NOTIFICATION_SEND_FAILED, message, cause);
    }
}
