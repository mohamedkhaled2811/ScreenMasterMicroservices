package com.gr74.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * The machine-readable error contract for this service.
 *
 * <p>Every error response carries one of these constants as the {@code code} property of an RFC 9457
 * {@code ProblemDetail} (see {@link GlobalExceptionHandler}). The {@code code} — not the HTTP status,
 * not the human-readable {@code detail} — is what a <em>client</em> or a sibling service branches on:
 * the wording of a message may change, but the enum name is a stable promise. Adding a constant is a
 * backwards-compatible change; renaming one is a breaking contract change.
 *
 * <p>Each constant pins the HTTP status it maps to so the status and the code can never drift apart
 * (the same outcome always returns the same pair).
 */
public enum PaymentErrorCode {

    /** The request body or headers failed validation (e.g. missing field, non-positive amount). */
    PAYMENT_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** No payment exists for the requested identifier. */
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),

    /** Booking has no such booking — we will not open a checkout for something that isn't there. */
    PAYMENT_BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),

    /** The booking belongs to a different user than the one making the request. */
    PAYMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "Not your booking"),

    /** The booking is in a state that cannot be paid for (already CONFIRMED, CANCELLED, ...). */
    PAYMENT_BOOKING_NOT_PAYABLE(HttpStatus.CONFLICT, "Booking is not payable"),

    /**
     * The booking's seat hold has lapsed. Distinct from a lapsed <em>session</em>, which is
     * recoverable: here the seats may already belong to someone else, so the user must book again.
     */
    PAYMENT_BOOKING_EXPIRED(HttpStatus.CONFLICT, "Booking has expired"),

    /** This booking is already paid for. Retrying would be a double charge. */
    PAYMENT_ALREADY_PAID(HttpStatus.CONFLICT, "Payment already completed"),

    /** The requested gateway is not registered in this deployment (no credentials configured). */
    PAYMENT_GATEWAY_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "Payment gateway not available"),

    /** The requested gateway cannot settle this booking's currency (e.g. Paymob asked for USD). */
    PAYMENT_CURRENCY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "Currency not supported by this gateway"),

    /**
     * The gateway could not be reached or did not answer in time. Distinct from a decline, which is
     * a successful call with a negative outcome. The attempt is left PENDING for reconciliation.
     *
     * <p>This is also the answer when the circuit breaker is OPEN (fast-fail instead of a wait): the
     * gateway is down, and the caller sees the same coded 503 it already handles for a real outage —
     * just returned in microseconds instead of after a timeout. See
     * {@code docs/concepts/resilience-patterns.md}.
     */
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable"),

    /**
     * The gateway's <em>bulkhead</em> is full — this deployment is already running the maximum
     * number of concurrent calls to that gateway and rejected this one immediately rather than
     * queue it. Deliberately a different code (429) from an outage (503): "we are saturated, try
     * again shortly" is not the same as "the gateway is down", and a client that branches on
     * {@code code} can tell the difference (and back off instead of retrying an outage). A 429
     * never means a payment was charged or even attempted. See
     * {@code docs/concepts/resilience-patterns.md}.
     */
    PAYMENT_GATEWAY_BUSY(HttpStatus.TOO_MANY_REQUESTS, "Payment gateway busy"),

    /** Booking could not be reached, so we cannot verify the amount. We fail closed, never open. */
    PAYMENT_BOOKING_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Booking service unavailable"),

    /** A webhook's signature did not verify — forged, or our secret is misconfigured. */
    PAYMENT_WEBHOOK_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "Webhook signature invalid"),

    /** A refund would exceed what remains refundable on the payment. */
    PAYMENT_REFUND_EXCEEDS_REMAINING(HttpStatus.CONFLICT, "Refund exceeds remaining refundable amount"),

    /** A refund was requested against a payment that was never captured. */
    PAYMENT_NOT_REFUNDABLE(HttpStatus.CONFLICT, "Payment is not refundable"),

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
