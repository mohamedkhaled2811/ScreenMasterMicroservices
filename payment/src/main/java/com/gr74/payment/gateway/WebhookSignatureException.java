package com.gr74.payment.gateway;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * A webhook's signature did not verify.
 *
 * <p>This is the one case where we answer a gateway with a non-2xx (400): either the delivery is
 * forged, or our webhook secret is misconfigured — and both need a human, not a retry.
 *
 * <p>The delivery is still <b>stored</b>, with {@code signatureValid = false}. A run of such rows
 * from one source is an attack signature, and discarding them would discard the evidence.
 */
public class WebhookSignatureException extends PaymentException {

    public WebhookSignatureException(PaymentGatewayType gateway, String message) {
        super(PaymentErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID,
                "Webhook signature verification failed for " + gateway + ": " + message);
    }
}
