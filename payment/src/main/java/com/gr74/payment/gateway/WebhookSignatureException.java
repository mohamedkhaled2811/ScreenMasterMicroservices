package com.gr74.payment.gateway;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.model.PaymentGatewayType;

/**
 * Webhook signature did not verify. Delivery is still stored with signatureValid=false; answered 400.
 */
public class WebhookSignatureException extends PaymentException {

    public WebhookSignatureException(PaymentGatewayType gateway, String message) {
        super(PaymentErrorCode.PAYMENT_WEBHOOK_SIGNATURE_INVALID,
                "Webhook signature verification failed for " + gateway + ": " + message);
    }
}
