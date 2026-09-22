package com.gr74.payment.webhook;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.WebhookSignatureException;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.WebhookEvent;
import com.gr74.payment.model.WebhookProcessingStatus;
import com.gr74.payment.repository.WebhookEventRepository;
import com.gr74.payment.service.WebhookWriter;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Verifies a delivery, stores the evidence, then applies the outcome in two transactions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookProcessor {

    private final GatewayRegistry registry;
    private final WebhookWriter writer;
    private final WebhookEventRepository events;

    /**
     * Handles one gateway delivery: verify the signature, store the evidence, dedupe, apply.
     *
     * @param type which gateway claims to have sent this (already validated from the path)
     * @param rawBody the exact bytes received — signatures are computed over these
     * @param headers delivery headers (signature + metadata), keys lower-cased by the controller
     */
    @Observed(name = "payment.webhook.process", contextualName = "process-webhook")
    public WebhookResult process(PaymentGatewayType type, byte[] rawBody, Map<String, String> headers) {
        PaymentGateway gateway = registry.require(type);
        GatewayEvent event;
        try {
            event = gateway.parseAndVerifyWebhook(asString(rawBody), headers);
        } catch (WebhookSignatureException e) {
            // Store the tampered delivery as evidence, then answer 400.
            writer.storeInvalidEvent(type, rawBody, headers);
            log.warn("Rejected webhook gateway={}: {}", type, e.getMessage());
            return WebhookResult.badSignature();
        }

        WebhookEvent stored = writer.storeVerifiedEvent(type, event, rawBody, headers);
        if (stored == null) {
            // Dedupe fired on UNIQUE (gateway, event_id); re-run the apply if never applied.
            WebhookEvent prior = events.findByGatewayAndEventId(type, event.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Dedupe fired for gateway=" + type + " eventId=" + event.eventId()
                                    + " but the winning row is unreadable"));
            if (prior.getProcessingStatus() == WebhookProcessingStatus.PROCESSED) {
                return WebhookResult.duplicate(); // 200, no-op
            }
            // RECEIVED but never applied: re-run the apply; guarded transitions make it safe.
            log.info("Re-running apply for gateway={} eventId={} (stored {} but never applied)",
                    type, event.eventId(), prior.getProcessingStatus());
            stored = prior;
        }

        if (event.isRefund()) {
            // Refund vocabulary routes to the refund ledger, correlated by gateway refund id.
            return writer.markRefundReceived(type, event, stored.getId());
        }
        // Business state change and outbox row commit together.
        return writer.applyOutcome(type, event, stored.getId());
    }

    /** Applies reconciliation's answer through the same evidence insert and apply path. */
    public WebhookResult applyReconciled(PaymentGatewayType type, GatewayEvent event) {
        WebhookEvent stored = writer.storeVerifiedEvent(type, event,
                reconBody(event), Map.of("source", "reconciliation"));
        if (stored == null) {
            // A second sweep already recorded this answer; the dedupe fired.
            WebhookEvent prior = events.findByGatewayAndEventId(type, event.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Dedupe fired for gateway=" + type + " eventId=" + event.eventId()
                                    + " but the winning row is unreadable"));
            if (prior.getProcessingStatus() == WebhookProcessingStatus.PROCESSED) {
                log.info("Reconciliation answer gateway={} eventId={} already applied — no-op",
                        type, event.eventId());
                return WebhookResult.duplicate();
            }
            log.info("Re-running reconciled apply for gateway={} eventId={} (stored {} but never applied)",
                    type, event.eventId(), prior.getProcessingStatus());
            stored = prior;
        }
        if (event.isRefund()) {
            return writer.markRefundReceived(type, event, stored.getId());
        }
        return writer.applyOutcome(type, event, stored.getId());
    }

    /** Stored body for a synthetic reconciliation event. */
    private static byte[] reconBody(GatewayEvent event) {
        String json = "{\"reconAttemptId\":\"" + event.gatewaySessionId()
                + "\",\"status\":\"" + event.status() + "\"}";
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String asString(byte[] rawBody) {
        return rawBody == null ? "" : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
    }
}
