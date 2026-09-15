package com.gr74.payment.exception;

import com.gr74.payment.model.PaymentGatewayType;

/**
 * The gateway was <em>reachable but saturated</em>: its resilience bulkhead was full and the call
 * was rejected immediately rather than queued.
 *
 * <p>Deliberately distinct from {@code GatewayException} (an outage → 503). A full bulkhead means
 * "this deployment is already running the maximum concurrent calls to that gateway — try again
 * shortly", which is {@code PAYMENT_GATEWAY_BUSY} (429). The distinction matters because the two
 * ask different things of a caller: back off on a 429, stop-and-wait on a 503.
 *
 * <p>Thrown by {@link com.gr74.payment.gateway.ResilientPaymentGateway} when the resilience4j
 * bulkhead rejects a call — the decorator translates {@code BulkheadFullException} into this so the
 * caller never sees a resilience-library exception (and so a bulkhead rejection can never leak as a
 * 500). See {@code docs/concepts/resilience-patterns.md}.
 */
public class GatewayBusyException extends PaymentException {

    public GatewayBusyException(PaymentGatewayType gateway, String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_BUSY,
                "Gateway " + gateway + " is at capacity: " + message, cause);
    }

    public GatewayBusyException(PaymentGatewayType gateway, String message) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_BUSY,
                "Gateway " + gateway + " is at capacity: " + message);
    }
}