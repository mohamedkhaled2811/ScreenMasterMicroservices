package com.gr74.notification.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for this service.
 *
 * <p>Notification is a consumer-only service: it has no business controllers, so most of these
 * constants travel as an HTTP {@code code} only through the security filter chain (rendered by
 * {@code SecurityProblemSupport} — there is deliberately no {@code @RestControllerAdvice}, nothing
 * would use it) — and as log-line codes on the consumer path. The enum exists to keep the repo's
 * standing convention ("every service has an {@code exception/} package with a coded base
 * exception") honest, and so that the moment this service grows an HTTP surface, the coded-error
 * contract is already in place. Mirror of {@code payment/exception/PaymentErrorCode}.
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart.
 */
public enum NotificationErrorCode {

    /**
     * No (or an invalid) Bearer token on an HTTP call. Rendered by the security filter chain via
     * {@code SecurityProblemSupport} — kept distinct from {@link #NOTIFICATION_FORBIDDEN} on
     * purpose: "log in" and "ask an admin" are different answers.
     */
    NOTIFICATION_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /**
     * A valid token without the role the endpoint needs. Same filter-chain rendering as
     * {@link #NOTIFICATION_UNAUTHORIZED}.
     */
    NOTIFICATION_FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

    /**
     * Keycloak refused the service's own client-credentials grant (wrong secret, unknown client,
     * IdP down) — thrown by {@code MachineTokenProvider} when the machine token cannot be
     * obtained. The consumer treats it as retryable (the message stays queued); it is never an
     * HTTP status on its own, only ever a log-line/queue-retry code.
     */
    NOTIFICATION_IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Identity provider unavailable"),

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