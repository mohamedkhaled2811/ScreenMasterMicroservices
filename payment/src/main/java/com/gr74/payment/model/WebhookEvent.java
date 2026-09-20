package com.gr74.payment.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One inbound webhook delivery from a gateway, stored <b>raw and before any business logic runs</b>.
 *
 * <p>This table does two jobs at once, and the second is why it holds the payload:
 *
 * <ol>
 *   <li><b>Idempotency.</b> {@code UNIQUE (gateway, event_id)} keyed on the <em>gateway's own</em>
 *       event id means the second delivery of the same event fails the insert; the handler returns
 *       200 having done nothing. Because the dedupe <em>is</em> the insert, there is no window in
 *       which an event is processed but unstored.</li>
 *   <li><b>Evidence.</b> The raw bytes settle "the gateway says it told us" disputes, let a fixed
 *       handler be replayed over stored rows after a processing bug, allow backfilling fields we
 *       ignore today (fees, card brand, 3DS result), and — via {@code signatureValid = false} rows —
 *       preserve the audit trail of a forgery attempt.</li>
 * </ol>
 *
 * <p>The payload is stored <em>exactly</em> as received, never a re-serialized DTO: signatures are
 * computed over exact bytes, so a Jackson round-trip (which reorders keys and changes whitespace)
 * would make later re-verification impossible.
 *
 * <p><b>These rows are data, not logs.</b> Hosted checkout keeps card numbers and CVV out of the
 * body, but billing name, email and last-4 can be in there — so payload bodies are never logged,
 * reads are admin-only, and a retention job prunes old rows.
 */
@Entity
@Table(
        name = "webhook_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_webhook_events_gateway_event_id",
                columnNames = {"gateway", "event_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING) // never ordinal
    @Column(nullable = false, length = 24, updatable = false)
    private PaymentGatewayType gateway;

    /** The gateway's OWN event id — the dedupe key. Never one we mint. */
    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    private String eventId;

    /** The gateway's raw type string, kept unnormalized on purpose for forensics. */
    @Column(name = "event_type", updatable = false, length = 120)
    private String eventType;

    /** The exact raw body as received. */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    /** Signature and delivery headers as received, with secrets scrubbed. */
    @Column(updatable = false, columnDefinition = "TEXT")
    private String headers;

    /** {@code false} rows are kept deliberately — they are the forged-webhook audit trail. */
    @Column(name = "signature_valid", nullable = false, updatable = false)
    private boolean signatureValid;

    /**
     * The attempt this delivery turned out to belong to. Null when the session id matched nothing —
     * which is still stored, and still answered 200 (an unknown session is not the gateway's
     * problem to retry).
     */
    @Column(name = "payment_attempt_id")
    private Long paymentAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 24)
    private WebhookProcessingStatus processingStatus;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /**
     * The trace id this delivery was processed under (Phase 6, plan 2.3). A webhook arrives with no
     * {@code traceparent} and starts a NEW trace by design; storing its id on the evidence row is
     * what links any stored payload back to exactly what it did — the "customer says I paid and
     * nothing happened" debugging path. Null when no trace was live (a test, or a synthetic
     * reconciliation delivery with tracing disabled).
     */
    @Column(name = "trace_id", updatable = false, length = 32)
    private String traceId;

    public WebhookEvent(PaymentGatewayType gateway, String eventId, String eventType,
            String payload, String headers, boolean signatureValid) {
        this(gateway, eventId, eventType, payload, headers, signatureValid, null);
    }

    public WebhookEvent(PaymentGatewayType gateway, String eventId, String eventType,
            String payload, String headers, boolean signatureValid, String traceId) {
        this.gateway = gateway;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.headers = headers;
        this.signatureValid = signatureValid;
        this.traceId = traceId;
        this.processingStatus = WebhookProcessingStatus.RECEIVED;
        this.receivedAt = Instant.now();
    }

    /** Record the outcome of processing this delivery, and what it was about. */
    public void recordOutcome(WebhookProcessingStatus status, Long paymentAttemptId) {
        this.processingStatus = status;
        this.paymentAttemptId = paymentAttemptId;
        this.processedAt = Instant.now();
    }
}
