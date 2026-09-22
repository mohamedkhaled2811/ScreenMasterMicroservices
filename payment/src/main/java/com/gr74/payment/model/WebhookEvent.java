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
 * One inbound webhook delivery, stored raw before business logic runs.
 * UNIQUE (gateway, event_id) dedupes redelivery; payload is kept byte-exact for re-verification.
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

    /** Gateway's own event id — the dedupe key. */
    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    private String eventId;

    /** Gateway raw type string, kept unnormalized. */
    @Column(name = "event_type", updatable = false, length = 120)
    private String eventType;

    /** Raw body as received. */
    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    /** Delivery headers with secrets scrubbed. */
    @Column(updatable = false, columnDefinition = "TEXT")
    private String headers;

    /** False rows are kept as the forgery audit trail. */
    @Column(name = "signature_valid", nullable = false, updatable = false)
    private boolean signatureValid;

    /** Matched attempt, or null when the session id matched nothing (still answered 200). */
    @Column(name = "payment_attempt_id")
    private Long paymentAttemptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 24)
    private WebhookProcessingStatus processingStatus;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** Trace id under which this delivery was processed; links payload to its effects. */
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

    /** Record processing outcome and matched attempt. */
    public void recordOutcome(WebhookProcessingStatus status, Long paymentAttemptId) {
        this.processingStatus = status;
        this.paymentAttemptId = paymentAttemptId;
        this.processedAt = Instant.now();
    }
}
