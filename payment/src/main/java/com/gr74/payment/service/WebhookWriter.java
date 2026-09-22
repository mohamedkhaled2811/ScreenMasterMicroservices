package com.gr74.payment.service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.gr74.payment.model.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.RabbitConfig;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.messaging.PaymentFailedEvent;
import com.gr74.payment.messaging.PaymentSucceededEvent;
import com.gr74.payment.outbox.OutboxEventType;
import com.gr74.payment.outbox.OutboxMessage;
import com.gr74.payment.outbox.OutboxMessageRepository;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;
import com.gr74.payment.repository.RefundRepository;
import com.gr74.payment.repository.WebhookEventRepository;
import com.gr74.payment.webhook.WebhookResult;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Committed writes on the webhook path: evidence store plus outcome applied with its outbox row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookWriter {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final RefundRepository refunds;
    private final WebhookEventRepository events;
    private final OutboxMessageRepository outbox;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    /** Stores a delivery whose signature did not verify, in its own committed transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookEvent storeInvalidEvent(PaymentGatewayType type, byte[] rawBody,
            Map<String, String> headers) {
        WebhookEvent stored = new WebhookEvent(type, "invalid-" + UUID.randomUUID(),
                null, asString(rawBody), headersJson(headers), false, currentTraceId());
        return events.saveAndFlush(stored);
    }

    /**
     * Stores a verified delivery; returns null when the UNIQUE (gateway, event_id) insert fires (dedupe).
     * Commits on its own so evidence survives a later rolled-back business transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookEvent storeVerifiedEvent(PaymentGatewayType type, GatewayEvent event,
            byte[] rawBody, Map<String, String> headers) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            // Mint a key so the delivery is still stored, answered 200, and processed once.
            eventId = "no-id-" + UUID.randomUUID();
        }
        WebhookEvent stored = new WebhookEvent(type, eventId, event.rawEventType(),
                asString(rawBody), headersJson(headers), true, currentTraceId());
        try {
            return events.saveAndFlush(stored);
        } catch (DataIntegrityViolationException duplicate) {
            // Dedupe fired; roll back the failed flush rather than committing an empty shell.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("Duplicate webhook delivery gateway={} eventId={}; dedupe fired", type, eventId);
            return null;
        }
    }

    /**
     * Applies a verified payment event and announces it with an outbox row in one transaction.
     * Unknown sessions and uninteresting types are stored and answered 200.
     */
    @Transactional
    public WebhookResult applyOutcome(PaymentGatewayType type, GatewayEvent event, Long webhookEventId) {
        if (event.gatewaySessionId() == null || event.gatewaySessionId().isBlank()) {
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, null);
            return WebhookResult.ignored("missing session id");
        }
        Optional<PaymentAttempt> found =
                attempts.findByGatewayAndGatewaySessionId(type, event.gatewaySessionId());
        if (found.isEmpty()) {
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, null);
            return WebhookResult.ignored("unknown session");
        }
        if (!event.isActionable()) {
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, found.get().getId());
            return WebhookResult.ignored("uninteresting type");
        }

        PaymentAttempt attempt = found.get();
        // open-in-view is off, so load the payment inside this transaction.
        Payment payment = payments.findWithAttemptsById(attempt.getPayment().getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment for attempt " + attempt.getId() + " vanished mid-webhook"));
        tagWebhookSpan(attempt.getId(), payment.getId(), payment.getBookingId());

        // Terminal-state guard: false when the attempt is already terminal (drops out-of-order events).
        if (!attempt.transitionTo(event.status(), event.gatewayPaymentId(), event.failureReason())) {
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, attempt.getId());
            return WebhookResult.processedAsStale(attempt.getId());
        }

        boolean stateChanged = switch (event.status()) {
            case SUCCEEDED -> payment.markPaid();
            case FAILED -> payment.markFailed();
            // A lapsed session closes the attempt only; the payment stays PENDING for Pay Again.
            default -> false;
        };
        if (stateChanged) {
            // Same transaction as the state change: one outbox row per actual change.
            outbox.save(buildOutboxMessage(payment, attempt, event));
        }
        markProcessed(webhookEventId, WebhookProcessingStatus.PROCESSED, attempt.getId());
        log.info("Applied webhook gateway={} eventType={} attemptId={} paymentId={} status={} outbox={}",
                type, event.rawEventType(), attempt.getId(), payment.getId(),
                payment.getStatus(), stateChanged ? "written" : "skipped (no change)");
        return WebhookResult.processed(attempt.getId());
    }

    /**
     * Records a refund-vocabulary event; the only path by which a refund reaches SUCCEEDED.
     * Correlated by gateway refund id; idempotent on redelivery.
     */
    @Transactional
    public WebhookResult markRefundReceived(PaymentGatewayType type, GatewayEvent event, Long webhookEventId) {
        if (event.refundStatus() == null) {
            // Outcome not yet known: stored as evidence, answered 200, applied to nothing.
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, null);
            return WebhookResult.ignored("refund outcome not yet known");
        }

        Refund refund = correlateRefund(type, event);
        if (refund == null) {
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, null);
            log.warn("Refund webhook for unknown refund gateway={} eventType={} refundId={} paymentId={} — "
                            + "stored, answered 200, applied to nothing",
                    type, event.rawEventType(), event.gatewayRefundId(), event.gatewayPaymentId());
            return WebhookResult.ignored("unknown refund");
        }

        if (event.refundStatus() == RefundStatus.FAILED) {
            boolean changed = refund.markFailed();
            markProcessed(webhookEventId, WebhookProcessingStatus.PROCESSED, null);
            log.info("Refund id={} paymentId={} marked FAILED by webhook gateway={} eventType={} (redelivery={})",
                    refund.getId(), refund.getPayment().getId(), type, event.rawEventType(), !changed);
            return WebhookResult.processed(null);
        }

        // SUCCEEDED: confirm exactly once; redeliveries are no-ops via the terminal-state guard.
        String refundId = event.gatewayRefundId() != null
                ? event.gatewayRefundId()
                : refund.getGatewayRefundId();
        if (!refund.markSucceeded(refundId)) {
            markProcessed(webhookEventId, WebhookProcessingStatus.PROCESSED, null);
            log.info("Redelivered refund webhook for already-terminal refund id={} — no-op", refund.getId());
            return WebhookResult.processed(null);
        }
        Payment payment = payments.findById(refund.getPayment().getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment for refund " + refund.getId() + " vanished mid-webhook"));
        payment.applyRefund(refund.getAmount());
        markProcessed(webhookEventId, WebhookProcessingStatus.PROCESSED, null);
        log.info("Refund id={} paymentId={} confirmed by webhook gateway={} eventType={} amount={} "
                        + "refundedTotal={} paymentStatus={}",
                refund.getId(), payment.getId(), type, event.rawEventType(), refund.getAmount(),
                payment.getRefundedAmount(), payment.getStatus());
        return WebhookResult.processed(null);
    }

    /** Correlates a refund event by gateway refund id, with a transaction-id fallback. */
    private Refund correlateRefund(PaymentGatewayType type, GatewayEvent event) {
        if (event.gatewayRefundId() != null && !event.gatewayRefundId().isBlank()) {
            return refunds.findByGatewayRefundId(event.gatewayRefundId()).orElse(null);
        }
        if (event.gatewayPaymentId() == null || event.gatewayPaymentId().isBlank()) {
            return null;
        }
        return attempts.findByGatewayAndGatewayPaymentId(type, event.gatewayPaymentId())
                .map(attempt -> {
                    List<Refund> pending = refunds
                            .findByPaymentIdOrderByCreatedAtAsc(attempt.getPayment().getId()).stream()
                            .filter(r -> r.getStatus() == RefundStatus.PENDING)
                            .toList();
                    if (pending.size() != 1) {
                        log.warn("Ambiguous refund correlation gateway={} transactionId={}: {} PENDING refund(s) "
                                        + "— left for an operator",
                                type, event.gatewayPaymentId(), pending.size());
                        return null;
                    }
                    return pending.get(0);
                })
                .orElse(null);
    }

    /** Marks a stored delivery PROCESSED/IGNORED inside the caller's transaction. */
    private void markProcessed(Long webhookEventId, WebhookProcessingStatus status, Long attemptId) {
        if (webhookEventId == null) {
            return;
        }
        events.findById(webhookEventId).ifPresent(stored -> stored.recordOutcome(status, attemptId));
    }

    /** Tags the webhook span with the business ids it turned out to be about. */
    private void tagWebhookSpan(Long attemptId, Long paymentId, Long bookingId) {
        Span span = tracer.currentSpan();
        if (span == null) {
            return;
        }
        if (attemptId != null) {
            span.tag("attemptId", String.valueOf(attemptId));
        }
        if (paymentId != null) {
            span.tag("paymentId", String.valueOf(paymentId));
        }
        if (bookingId != null) {
            span.tag("bookingId", String.valueOf(bookingId));
        }
    }

    /** Returns the current trace id, or null when no trace is live. */
    private String currentTraceId() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context().traceId();
    }

    /** Returns the current span id, or null when no trace is live. */
    private String currentSpanId() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context().spanId();
    }

    private OutboxMessage buildOutboxMessage(Payment payment, PaymentAttempt attempt, GatewayEvent event) {
        try {
            if (event.status() == PaymentAttemptStatus.SUCCEEDED) {
                PaymentSucceededEvent announced = new PaymentSucceededEvent(
                        0L, // replaced with the outbox row id at relay time — see OutboxRelay
                        payment.getId(), payment.getBookingId(), attempt.getId(),
                        attempt.getGateway().name(), payment.getAmount(), payment.getCurrency(),
                        payment.getUserId(), java.time.Instant.now());
                return new OutboxMessage(OutboxEventType.PAYMENT_SUCCEEDED, payment.getId(),
                        RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY,
                        objectMapper.writeValueAsString(announced),
                        currentTraceId(), currentSpanId());
            }
            PaymentFailedEvent announced = new PaymentFailedEvent(
                    0L, payment.getId(), payment.getBookingId(), attempt.getId(),
                    attempt.getGateway().name(), event.failureReason(), java.time.Instant.now());
            return new OutboxMessage(OutboxEventType.PAYMENT_FAILED, payment.getId(),
                    RabbitConfig.PAYMENT_FAILED_ROUTING_KEY,
                    objectMapper.writeValueAsString(announced),
                    currentTraceId(), currentSpanId());
        } catch (JsonProcessingException e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                    "Could not serialize outbox event for payment " + payment.getId(), e);
        }
    }


    private static String asString(byte[] rawBody) {
        return rawBody == null ? "" : new String(rawBody, StandardCharsets.UTF_8);
    }

    /** Serializes delivery headers with secret-bearing values redacted. */
    private String headersJson(Map<String, String> headers) {
        try {
            Map<String, String> scrubbed = new LinkedHashMap<>();
            if (headers != null) {
                headers.forEach((name, value) -> scrubbed.put(name,
                        isSensitive(name) ? "***" : value));
            }
            return objectMapper.writeValueAsString(scrubbed);
        } catch (JsonProcessingException e) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                    "Could not serialize webhook headers", e);
        }
    }

    private static boolean isSensitive(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        return lower.contains("secret") || lower.contains("signature") || lower.contains("token")
                || lower.contains("authorization") || lower.contains("hmac") || lower.contains("key");
    }
}
