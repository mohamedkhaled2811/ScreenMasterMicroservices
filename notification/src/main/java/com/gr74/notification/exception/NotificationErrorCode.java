package com.gr74.notification.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for this service.
 *
 * <p>Notification is a consumer-only service: it has no business controllers, so this enum currently
 * has nowhere to travel as an HTTP {@code code} — there is deliberately no
 * {@code @RestControllerAdvice} (nothing would use it). It exists to keep the repo's standing
 * convention ("every service has an {@code exception/} package with a coded base exception") honest,
 * and so that the moment this service grows an HTTP surface, the coded-error contract is already in
 * place. Mirror of {@code payment/exception/PaymentErrorCode}.
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart.
 */
public enum NotificationErrorCode {

    /** A fallback for anything we did not anticipate — never leak internals to the caller. */
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

    /** Short, human-readable summary used as the {@code ProblemDetail} title. */
    public String title() {
        return title;
    }
}