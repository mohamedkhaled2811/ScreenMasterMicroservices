package com.gr74.notification.exception;

import org.springframework.http.HttpStatus;

/**
 * Machine-readable error codes and their HTTP statuses.
 */
public enum NotificationErrorCode {

    /** Missing or invalid Bearer token. */
    NOTIFICATION_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /** Valid token without the required role. */
    NOTIFICATION_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

    /** Keycloak grant failed; retryable on the consumer path. */
    NOTIFICATION_IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable"),

    /** Delivery failed; propagates so the broker redelivers. */
    NOTIFICATION_SEND_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "Notification delivery failed"),

    /** User has no email address; not retryable, dead-letters. */
    NOTIFICATION_RECIPIENT_UNKNOWN(HttpStatus.UNPROCESSABLE_ENTITY, "No email address for user"),

    /** Fallback; never leak internals. */
    NOTIFICATION_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    NotificationErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    /** Short summary used as the ProblemDetail title. */
    public String title() {
        return title;
    }
}