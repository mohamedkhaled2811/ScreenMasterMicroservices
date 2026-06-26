package com.gr74.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for this service.
 *
 * <p>Every error response carries one of these constants as the {@code code} property of an RFC 9457
 * {@code ProblemDetail} (see {@link GlobalExceptionHandler}). The {@code code} — not the HTTP status,
 * not the human-readable {@code detail} — is what a <em>sibling</em> service (Booking's saga in
 * Phase 3) branches on: the wording of a message may change, but the enum name is a stable promise.
 * Adding a constant is a backwards-compatible change; renaming one is a breaking contract change.
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart
 * (the same outcome always returns the same pair).
 */
public enum PaymentErrorCode {

    /** The request body or headers failed validation (e.g. missing key, non-positive amount). */
    PAYMENT_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** No payment exists for the requested identifier. */
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),

    /** The downstream provider could not be reached or did not respond in time. */
    PAYMENT_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment provider unavailable"),

    /** A fallback for anything we did not anticipate — never leak internals to the caller. */
    PAYMENT_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

    private final HttpStatus status;
    private final String title;

    PaymentErrorCode(HttpStatus status, String title) {
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
