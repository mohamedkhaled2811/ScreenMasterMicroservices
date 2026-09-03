package com.gr74.payment.gateway;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * An <em>infrastructural</em> failure talking to a gateway: unreachable, timed out, 5xx, or a
 * malformed response.
 *
 * <p>Deliberately distinct from a business decline. A declined card is a <b>successful</b> call that
 * returns a {@code FAILED} status through the normal path; only "we could not get a trustworthy
 * answer" throws this. Confusing the two would mean retrying a decline (pointless) or treating an
 * outage as a decline (wrong, and it would strand the attempt).
 *
 * <p>Maps to {@code PAYMENT_GATEWAY_UNAVAILABLE} (503). The attempt row is deliberately left
 * {@code PENDING} for reconciliation to resolve — never rolled back, because the gateway may in fact
 * have created the session.
 */
public class GatewayException extends PaymentException {

    public GatewayException(PaymentGatewayType gateway, String message, Throwable cause) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Gateway " + gateway + " failed: " + message, cause);
    }

    public GatewayException(PaymentGatewayType gateway, String message) {
        super(PaymentErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Gateway " + gateway + " failed: " + message);
    }
}
