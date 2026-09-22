package com.gr74.payment.gateway.sandbox;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gr74.payment.config.PaymentProps;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers sandbox webhooks as signed HTTP through the front door, like a real gateway.
 * A failed delivery leaves the recorded outcome for reconciliation; returns whether accepted (2xx).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxWebhookClient {

    private final PaymentProps paymentProps;
    private final RestClient.Builder restClientBuilder;

    /**
     * POST a signed JSON payload to our own webhook endpoint.
     *
     * @param gateway         path segment to deliver to (e.g. {@code sandbox})
     * @param payload         exact JSON bytes, signed as-is
     * @param signature       HMAC over those bytes
     * @param signatureHeader header the adapter verifies
     * @return whether delivery was accepted (2xx)
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
            // Outcome stays recorded; reconciliation heals the missed delivery.
            log.warn("SANDBOX webhook delivery to {} failed (outcome stays recorded): {}", url, e.getMessage());
            return false;
        }
    }
}
