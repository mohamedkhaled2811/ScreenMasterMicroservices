package com.gr74.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable machine-readable error codes, each pinned to its HTTP status.
 */
public enum PaymentErrorCode {

    /** The request failed validation. */
    PAYMENT_VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

    /** No payment exists for the requested identifier. */
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),

    /** Booking has no such booking. */
    PAYMENT_BOOKING_NOT_FOUND(HttpStatus.NOT_FOUND, "Booking not found"),

    /** The booking belongs to a different user. */
    PAYMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "Not your booking"),

    /** The booking is in a state that cannot be paid for. */
    PAYMENT_BOOKING_NOT_PAYABLE(HttpStatus.CONFLICT, "Booking is not payable"),

    /** The booking's seat hold has lapsed; the user must book again. */
    PAYMENT_BOOKING_EXPIRED(HttpStatus.CONFLICT, "Booking has expired"),

    /** This booking is already paid for. */
    PAYMENT_ALREADY_PAID(HttpStatus.CONFLICT, "Payment already completed"),

    /** The requested gateway is not registered in this deployment. */
    PAYMENT_GATEWAY_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "Payment gateway not available"),

    /** The requested gateway cannot settle this booking's currency. */
    PAYMENT_CURRENCY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "Currency not supported by this gateway"),

    /** The gateway is unreachable or timed out; covers an open circuit breaker too. */
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable"),

    /** The gateway bulkhead is full; safe to retry shortly, nothing was charged. */
    PAYMENT_GATEWAY_BUSY(HttpStatus.TOO_MANY_REQUESTS, "Payment gateway busy"),

    /** Booking could not be reached, so the amount cannot be verified. */
    PAYMENT_BOOKING_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Booking service unavailable"),

    /** A webhook signature did not verify. */
    PAYMENT_WEBHOOK_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST, "Webhook signature invalid"),

    /** No (or an invalid) Bearer token. */
    PAYMENT_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),

    /** A valid token without the role the endpoint needs. */
    PAYMENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Access denied"),

    /** A refund would exceed what remains refundable on the payment. */
    PAYMENT_REFUND_EXCEEDS_REMAINING(HttpStatus.CONFLICT, "Refund exceeds remaining refundable amount"),

    /** A refund was requested against a payment that was never captured. */
    PAYMENT_NOT_REFUNDABLE(HttpStatus.CONFLICT, "Payment is not refundable"),

    /** Fallback for unanticipated errors; never leaks internals. */
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

    /** Short summary used as the ProblemDetail title. */
    public String title() {
        return title;
    }
}
