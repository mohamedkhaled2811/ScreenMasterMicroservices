package com.gr74.payment.gateway.sandbox;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gr74.payment.config.PaymentProps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers the sandbox's webhooks the way a real gateway would: signed HTTP through the front
 * door, not an in-process call.
 *
 * <p>Why the indirection matters: the payment service must not be able to tell the sandbox apart
 * from Stripe — the webhook arrives at {@code PAYMENT_PUBLIC_URL + /api/payments/webhooks/sandbox}
 * carrying a real HMAC, goes through signature verification, the evidence store, and the dedupe
 * like any other delivery. That is what lets a shell script drive the whole saga with curl, and
 * what makes the {@code deliverWebhook=false} partition stand-in honest (the outcome is recorded,
 * the delivery simply never happens).
 *
 * <p>Refund webhooks (step 3.5) reuse this same path — only the payload type differs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxWebhookClient {

    private final PaymentProps paymentProps;
    private final RestClient.Builder restClientBuilder;

    /**
     * POST a signed JSON payload to this service's own webhook endpoint.
     *
     * @param gateway         the path segment to deliver to (e.g. {@code sandbox})
     * @param payload         the exact JSON bytes to deliver — signed as-is
     * @param signature       the HMAC over those bytes
     * @param signatureHeader the header the adapter verifies (e.g. {@code x-sandbox-signature})
     * @return whether the delivery was accepted (any 2xx). A {@code false} is a network-partition
     *         equivalent: the outcome stays recorded and reconciliation recovers it.
     */
    public boolean deliver(String gateway, String payload, String signature, String signatureHeader) {
        String url = paymentProps.publicUrl() + "/api/payments/webhooks/" + gateway;
        try {
            restClientBuilder.build().post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(signatureHeader, signature)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("SANDBOX webhook delivered to {}", url);
            return true;
        } catch (RestClientException e) {
            // The outcome is already recorded in the sandbox ledger; a failed delivery is exactly
            // the partition the reconciliation sweep exists to heal — loud log, no throw.
            log.warn("SANDBOX webhook delivery to {} failed (outcome stays recorded): {}", url, e.getMessage());
            return false;
        }
    }
}
