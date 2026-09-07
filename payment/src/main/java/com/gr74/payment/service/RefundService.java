package com.gr74.payment.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.gr74.payment.dto.RefundResponse;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.gateway.GatewayRefundRequest;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.Refund;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;
import com.gr74.payment.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refunds, in the only order that is safe with money.
 *
 * <p>The same three-step shape {@link PaymentSessionFactory} uses for sessions, because the
 * constraint is the same: a database transaction cannot span an external gateway, and a row lock
 * must never be held across a network call.
 *
 * <pre>
 *   1. tx 1: lock the payment, validate the invariant, insert the refund PENDING -- committed
 *   2. call the gateway                                                      -- outside any tx
 *   3. tx 2: record what the gateway accepted (still PENDING)                 -- committed
 * </pre>
 *
 * <p>If step 2 times out, step 1 already committed: the refund sits PENDING and is marked FAILED,
 * and a retry creates a fresh refund under a fresh key (FAILED rows drop out of the invariant, so
 * they block nothing). If the gateway accepted but its confirming webhook never arrives, the
 * refund stays PENDING — the refund-side twin of the payment reconciliation gap, and a named,
 * accepted gap for this phase (3.6's sweep covers attempts, not refunds).
 *
 * <p>This bean itself is deliberately NOT transactional: each step needs its own boundary (see
 * {@link RefundWriter}'s class javadoc for why self-invocation would make them inert), and an
 * ambient transaction here would join tx 1 and tx 2 into the single commit the design exists to
 * avoid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final RefundWriter writer;
    private final GatewayRegistry registry;

    /**
     * Request a refund against a captured payment.
     *
     * @param paymentId       which obligation to refund
     * @param requestedAmount null means "the full remaining capacity"
     * @param reason          why (defaults to {@code CUSTOMER_REQUEST}); the auto-refund passes
     *                        {@code BOOKING_EXPIRED} / {@code BOOKING_CANCELLED}
     * @param idempotencyKey  de-duplicates redeliveries: the same key returns the same refund, so
     *                        the compensation listener derives it from the rejection's event id
     *                        ({@code "reject-" + eventId}) instead of keeping a
     *                        {@code processed_events} table
     */
    public RefundResponse requestRefund(long paymentId, BigDecimal requestedAmount,
            String reason, String idempotencyKey) {
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "refund-" + UUID.randomUUID()
                : idempotencyKey;
        String why = (reason == null || reason.isBlank()) ? "CUSTOMER_REQUEST" : reason;

        // Idempotency first: a redelivered rejection (or a retried admin call) collapses onto the
        // existing row before any lock is taken — a second delivery can never double-refund.
        Optional<Refund> existing = refunds.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("Refund idempotency hit key={} — returning the existing refund", key);
            return responseOf(existing.get());
        }

        Payment payment = writer.lockAndInsertPendingRefund(paymentId, requestedAmount, why, key);
        Refund pending = refunds.findByIdempotencyKey(key)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Refund " + key + " vanished between insert and gateway call"));

        RefundResult result;
        try {
            result = callGateway(payment, pending);
        } catch (RuntimeException e) {
            // No trustworthy answer: mark FAILED (committed, on its own) and let the error reach
            // the caller — a retry mints a FRESH key, so the unknown outcome is never inherited.
            writer.markRefundFailed(pending.getId(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }

        Refund recorded = writer.recordRefundOutcome(pending.getId(), result);
        return RefundResponse.of(recorded, payment.getCurrency());
    }

    /**
     * Ask the gateway that captured the money to return it. Resolved from the SUCCEEDED attempt —
     * the payment remembers <em>that</em> it is paid, the attempt remembers <em>where</em> (which
     * gateway, which transaction id). No gateway-specific branching here: the registry hands back
     * the adapter and the adapter owns the call.
     */
    private RefundResult callGateway(Payment payment, Refund refund) {
        PaymentAttempt succeeded = attempts
                .findByPaymentIdAndStatus(payment.getId(), PaymentAttemptStatus.SUCCEEDED)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment " + payment.getId() + " is refundable but has no SUCCEEDED attempt"));
        if (succeeded.getGatewayPaymentId() == null || succeeded.getGatewayPaymentId().isBlank()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                    "SUCCEEDED attempt " + succeeded.getId() + " names no gateway transaction");
        }
        PaymentGateway gateway = registry.require(succeeded.getGateway());
        return gateway.refund(new GatewayRefundRequest(
                succeeded.getGatewayPaymentId(),
                refund.getAmount(),
                payment.getCurrency(),
                refund.getIdempotencyKey(),
                refund.getReason()));
    }

    /** A response needs the payment's currency, which lives on the payment row, not the refund. */
    private RefundResponse responseOf(Refund refund) {
        Payment payment = payments.findById(refund.getPayment().getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment for refund " + refund.getId() + " vanished"));
        return RefundResponse.of(refund, payment.getCurrency());
    }
}
