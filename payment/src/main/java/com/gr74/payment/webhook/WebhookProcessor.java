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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The only path to {@code PAID}: verify, store the evidence, then apply — in two transactions.
 *
 * <p>The shape is two commits, not one, and the order is the point. The evidence row commits first
 * (its own {@code REQUIRES_NEW}) and survives a rolled-back business transaction; the business
 * write and the outbox row then commit together. A crash between the two leaves a {@code RECEIVED}
 * row with no applied outcome — and the next delivery of the same event re-runs the apply, which
 * is safe precisely because every transition underneath is a guarded, idempotent no-op.
 *
 * <p>This bean itself is deliberately NOT transactional: each step needs its own boundary, and an
 * ambient transaction here would join them into the single commit the design exists to avoid.
 * Reconciliation reuses this bean too ({@link #applyReconciled}) — same evidence insert, same
 * dedupe, same apply.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookProcessor {

    private final GatewayRegistry registry;
    private final WebhookWriter writer;
    private final WebhookEventRepository events;

    /**
     * Handle one gateway delivery: verify the signature, store the evidence, dedupe, apply.
     *
     * @param type     which gateway claims to have sent this (already validated from the path)
     * @param rawBody  the exact bytes received — signatures are computed over these, never over a
     *                 re-serialized form
     * @param headers  delivery headers (signature + metadata), keys lower-cased by the controller
     */
    public WebhookResult process(PaymentGatewayType type, byte[] rawBody, Map<String, String> headers) {
        PaymentGateway gateway = registry.require(type);
        GatewayEvent event;
        try {
            event = gateway.parseAndVerifyWebhook(asString(rawBody), headers);
        } catch (WebhookSignatureException e) {
            // Evidence first: the forgery (or misconfiguration) is stored with signature_valid=false
            // in its own committed transaction, THEN the caller answers 400. The audit trail is the
            // point of webhook_events — discarding a tampered delivery would discard the attack log.
            writer.storeInvalidEvent(type, rawBody, headers);
            log.warn("Rejected webhook gateway={}: {}", type, e.getMessage());
            return WebhookResult.badSignature();
        }

        WebhookEvent stored = writer.storeVerifiedEvent(type, event, rawBody, headers);
        if (stored == null) {
            // The UNIQUE (gateway, event_id) insert fired — the dedupe IS the insert, not a
            // read-then-insert (which would leave a race window between the check and the write).
            WebhookEvent prior = events.findByGatewayAndEventId(type, event.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Dedupe fired for gateway=" + type + " eventId=" + event.eventId()
                                    + " but the winning row is unreadable"));
            if (prior.getProcessingStatus() == WebhookProcessingStatus.PROCESSED) {
                return WebhookResult.duplicate(); // 200, no-op
            }
            // RECEIVED but never applied: the first attempt died between the two transactions.
            // Re-run the apply — safe because every transition below is a guarded idempotent no-op.
            // Do not "optimize" this re-run away; it is the crash-safety property.
            log.info("Re-running apply for gateway={} eventId={} (stored {} but never applied)",
                    type, event.eventId(), prior.getProcessingStatus());
            stored = prior;
        }

        if (event.isRefund()) {
            // Refund vocabulary routes to the refund ledger, not to applyOutcome: correlation is
            // by gateway refund id (Stripe's charge.refunded carries no session id), and only the
            // webhook promotes a refund to SUCCEEDED.
            return writer.markRefundReceived(type, event, stored.getId());
        }
        // Business state changes AND the outbox row commit together — the dual-write problem is
        // exactly this call.
        return writer.applyOutcome(type, event, stored.getId());
    }

    /**
     * Apply reconciliation's answer through the SAME path a webhook takes: the same evidence
     * insert (so the synthetic {@code recon:{attemptId}:{status}} id joins the dedupe), the same
     * RECEIVED-re-run, the same {@code applyOutcome}.
     *
     * <p>No signature to verify — reconciliation just asked the gateway over its own TLS, so
     * there is nothing to distrust. The stored body records which attempt and status this
     * synthesis was about, and the headers mark its provenance for forensics.
     */
    public WebhookResult applyReconciled(PaymentGatewayType type, GatewayEvent event) {
        WebhookEvent stored = writer.storeVerifiedEvent(type, event,
                reconBody(event), Map.of("source", "reconciliation"));
        if (stored == null) {
            // A second sweep (or an overlapping tick) already recorded this exact answer — the
            // dedupe fired, which is the collapse working as designed, not an error.
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

    /** The synthetic event's stored body — evidence like any other delivery, minus the network. */
    private static byte[] reconBody(GatewayEvent event) {
        String json = "{\"reconAttemptId\":\"" + event.gatewaySessionId()
                + "\",\"status\":\"" + event.status() + "\"}";
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String asString(byte[] rawBody) {
        return rawBody == null ? "" : new String(rawBody, java.nio.charset.StandardCharsets.UTF_8);
    }
}
