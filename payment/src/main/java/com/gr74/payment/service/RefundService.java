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
 * Requests refunds: insert PENDING, call the gateway outside any transaction, record the outcome.
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
     * Requests a refund against a captured payment.
     *
     * @param paymentId which obligation to refund
     * @param requestedAmount null means "the full remaining capacity"
     * @param reason why (defaults to {@code CUSTOMER_REQUEST})
     * @param idempotencyKey de-duplicates redeliveries: the same key returns the same refund
     */
    public RefundResponse requestRefund(long paymentId, BigDecimal requestedAmount,
            String reason, String idempotencyKey) {
        String key = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "refund-" + UUID.randomUUID()
                : idempotencyKey;
        String why = (reason == null || reason.isBlank()) ? "CUSTOMER_REQUEST" : reason;

        // Idempotency first: a redelivery collapses onto the existing row before any lock is taken.
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
            // No trustworthy answer: mark FAILED so a retry mints a fresh key.
            writer.markRefundFailed(pending.getId(),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }

        Refund recorded = writer.recordRefundOutcome(pending.getId(), result);
        return RefundResponse.of(recorded, payment.getCurrency());
    }

    /** Asks the gateway that captured the money to return it, resolved from the SUCCEEDED attempt. */
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

    /** Builds a response; the currency lives on the payment row, not the refund. */
    private RefundResponse responseOf(Refund refund) {
        Payment payment = payments.findById(refund.getPayment().getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment for refund " + refund.getId() + " vanished"));
        return RefundResponse.of(refund, payment.getCurrency());
    }
}
