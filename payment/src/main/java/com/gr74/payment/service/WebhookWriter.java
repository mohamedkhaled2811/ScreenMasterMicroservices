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
 * Every committed write on the <b>webhook</b> path — the evidence store and the one transaction
 * where an outcome is both decided and announced.
 *
 * <p>A separate bean for the same reason {@link PaymentWriter} is: a {@code @Transactional} method
 * invoked on {@code this} is not transactional at all, because Spring's proxy is bypassed on
 * self-invocation. {@code WebhookProcessor} <em>injects</em> this bean, so every boundary below is
 * a real proxy hop — which matters more here than anywhere else in the service, because the whole
 * design is <b>two commits, deliberately not one</b>: the evidence row survives a rolled-back
 * business transaction, and the business write plus its outbox row commit together.
 *
 * <p>Split out of {@code PaymentWriter} once Phase 3 gave that class four unrelated callers
 * (checkout, webhooks, refunds, the relay). The transaction semantics are unchanged by the move —
 * only the file boundary is new.
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

    /**
     * Store a delivery whose signature did NOT verify — and keep it, in its own committed
     * transaction, before the caller answers 400.
     *
     * <p>A run of {@code signature_valid = false} rows from one source is an attack signature, and
     * discarding them would discard the evidence. The gateway never gave us a trustworthy event id
     * (its bytes are untrusted by definition here), so the dedupe key is minted — these rows never
     * collide with a real delivery and never dedupe against each other.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookEvent storeInvalidEvent(PaymentGatewayType type, byte[] rawBody,
            Map<String, String> headers) {
        WebhookEvent stored = new WebhookEvent(type, "invalid-" + UUID.randomUUID(),
                null, asString(rawBody), headersJson(headers), false, currentTraceId());
        return events.saveAndFlush(stored);
    }

    /**
     * Store a verified delivery. Returns the stored row — or {@code null} when the
     * {@code UNIQUE (gateway, event_id)} insert fails, which IS the dedupe: no read-then-insert, so
     * there is no window in which two concurrent deliveries of the same event both pass a check.
     *
     * <p>{@code REQUIRES_NEW} so the evidence commits even if the business transaction that follows
     * rolls back: the row is what lets an operator replay the delivery after fixing the bug.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookEvent storeVerifiedEvent(PaymentGatewayType type, GatewayEvent event,
            byte[] rawBody, Map<String, String> headers) {
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank()) {
            // A validly-signed delivery with no usable event id cannot join the dedupe — mint a key
            // so it is still stored, still answered 200, and still processed exactly once.
            eventId = "no-id-" + UUID.randomUUID();
        }
        WebhookEvent stored = new WebhookEvent(type, eventId, event.rawEventType(),
                asString(rawBody), headersJson(headers), true, currentTraceId());
        try {
            return events.saveAndFlush(stored);
        } catch (DataIntegrityViolationException duplicate) {
            // The dedupe fired: a concurrent (or redelivered) insert won. This transaction did
            // nothing else, but the failed flush leaves the persistence context dirty — roll it back
            // explicitly rather than committing an empty shell over a failed insert.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("Duplicate webhook delivery gateway={} eventId={}; dedupe fired", type, eventId);
            return null;
        }
    }

    /**
     * Apply a verified payment event to its attempt and payment, and — only if something actually
     * changed — announce it with an outbox row, all in ONE transaction.
     *
     * <p>That single atomicity is the entire point of the outbox: {@code PAID} and its
     * {@code PaymentSucceeded} row are one commit, so the dual-write problem (state without event,
     * or event without state) cannot happen. A redelivered event replays every guard below and
     * changes nothing, which is also what makes the RECEIVED-but-never-applied re-run safe.
     *
     * <p>Answers 200 to almost everything: an unknown session or an uninteresting event type is not
     * the gateway's problem — a non-2xx would trigger hours of retries for nothing.
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
        // open-in-view is off, so the payment is loaded inside this transaction rather than
        // traversed from the attempt's lazy proxy.
        Payment payment = payments.findWithAttemptsById(attempt.getPayment().getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment for attempt " + attempt.getId() + " vanished mid-webhook"));
        tagWebhookSpan(attempt.getId(), payment.getId(), payment.getBookingId());

        // The terminal-state guard: transitionTo returns false when the attempt is already terminal,
        // which is what drops the out-of-order case (a late FAILED must never overwrite SUCCEEDED).
        if (!attempt.transitionTo(event.status(), event.gatewayPaymentId(), event.failureReason())) {
            markProcessed(webhookEventId, WebhookProcessingStatus.IGNORED, attempt.getId());
            return WebhookResult.processedAsStale(attempt.getId());
        }

        boolean stateChanged = switch (event.status()) {
            case SUCCEEDED -> payment.markPaid();   // false when already PAID
            case FAILED -> payment.markFailed();
            // A lapsed or abandoned session closes the attempt, not the obligation: the payment
            // stays PENDING for Pay Again, and there is nothing for Booking to hear.
            default -> false;
        };
        if (stateChanged) {
            // Same transaction as the state change: publish exactly once per actual change, so a
            // redelivered event confirms exactly once.
            outbox.save(buildOutboxMessage(payment, attempt, event));
        }
        markProcessed(webhookEventId, WebhookProcessingStatus.PROCESSED, attempt.getId());
        log.info("Applied webhook gateway={} eventType={} attemptId={} paymentId={} status={} outbox={}",
                type, event.rawEventType(), attempt.getId(), payment.getId(),
                payment.getStatus(), stateChanged ? "written" : "skipped (no change)");
        return WebhookResult.processed(attempt.getId());
    }

    /**
     * Record the terminal handling of a refund-vocabulary event — the ONLY path by which a refund
     * reaches {@code SUCCEEDED} and money is counted as returned.
     *
     * <p>Correlation is by the gateway's <b>refund</b> id ({@code refunds.gateway_refund_id}), never
     * by the attempt-session lookup the payment path uses: Stripe's {@code charge.refunded} carries
     * no checkout-session id, so that lookup is null by construction there. The one exception is a
     * Paymob "original transaction reports refunded" callback, which names the transaction but no
     * refund id — that falls back to the transaction lookup and finalizes the payment's single
     * PENDING refund, and is IGNORED as ambiguous when there isn't exactly one.
     *
     * <p>Idempotent: a redelivered refund webhook replays {@code markSucceeded}/{@code markFailed},
     * which return false on an already-terminal row — the payment total is touched exactly once.
     */
    @Transactional
    public WebhookResult markRefundReceived(PaymentGatewayType type, GatewayEvent event, Long webhookEventId) {
        if (event.refundStatus() == null) {
            // A gateway's "refund accepted, outcome unknown" callback (Paymob pending): stored as
            // evidence, answered 200, applied to nothing — the confirming webhook comes later.
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

        // SUCCEEDED: confirm exactly once. The guard returns false on a redelivery (or on the
        // second of Paymob's twin callbacks for one refund) — the payment total must not move twice.
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

    /**
     * Which refund row a refund-vocabulary event belongs to: by gateway refund id first, falling
     * back to the original-transaction lookup only when the event names no refund id at all.
     */
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

    /** Mark a stored delivery PROCESSED/IGNORED inside the caller's transaction. */
    private void markProcessed(Long webhookEventId, WebhookProcessingStatus status, Long attemptId) {
        if (webhookEventId == null) {
            return;
        }
        events.findById(webhookEventId).ifPresent(stored -> stored.recordOutcome(status, attemptId));
    }

    /**
     * Tag the webhook's span — the {@code @Observed} on {@code WebhookProcessor.process} — with the
     * business ids it turned out to be about. A webhook arrives with NO
     * {@code traceparent} and starts a NEW trace by design: the payment outcome genuinely is a
     * separate causal chain, minutes after the booking request ended. These tags — plus the
     * {@code trace_id} stored on the evidence row — are therefore the only link between the
     * booking/session trace and the payment-outcome trace. No parent-child edge is faked.
     */
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

    /** The current trace id, or null when no trace is live — never throw (a scheduled path has none). */
    private String currentTraceId() {
        Span current = tracer.currentSpan();
        return current == null ? null : current.context().traceId();
    }

    /** The current span id, or null when no trace is live — never throw (a scheduled path has none). */
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

    /**
     * Serialize delivery headers with secret-bearing values scrubbed. The signature header itself
     * is per-payload (not a reusable secret) but reveals nothing useful to a future reader either,
     * so it is redacted along with tokens and keys.
     */
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
