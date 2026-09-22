package com.gr74.payment.service;

import java.math.BigDecimal;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.exception.PaymentNotFoundException;
import com.gr74.payment.gateway.RefundResult;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.model.Refund;
import com.gr74.payment.model.RefundStatus;
import com.gr74.payment.repository.PaymentRepository;
import com.gr74.payment.repository.RefundRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Committed writes on the refund path: lock and insert PENDING, then record the gateway outcome.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundWriter {

    private final PaymentRepository payments;
    private final RefundRepository refunds;

    /**
     * Locks the payment, validates remaining capacity, and inserts the refund PENDING.
     *
     * @param requested null means "the full remaining capacity"
     * @return the payment as it stands after the insert (detached — the transaction committed)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment lockAndInsertPendingRefund(long paymentId, BigDecimal requested,
            String reason, String idempotencyKey) {
        Payment payment = payments.lockById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment " + paymentId + " not found"));
        if (payment.getStatus() != PaymentStatus.PAID
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE,
                    "Payment " + paymentId + " is " + payment.getStatus() + " — only captured money can be refunded");
        }

        BigDecimal committed = refunds.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED)
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal capacity = payment.getAmount().subtract(committed);

        BigDecimal amount = requested;
        if (amount == null) {
            // A full refund with nothing left reports EXCEEDS like any over-capacity request.
            if (capacity.compareTo(BigDecimal.ZERO) <= 0) {
                throw new PaymentException(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING,
                        "Payment " + paymentId + " has nothing left to refund");
            }
            amount = capacity;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                    "Refund amount must be positive");
        }
        // compareTo, not equals: 500.0 and 500.00 compare equal in value but not as BigDecimals.
        if (amount.compareTo(capacity) > 0) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING,
                    "Refund of " + amount + " exceeds the remaining refundable " + capacity
                            + " on payment " + paymentId);
        }

        Refund refund = new Refund(amount, reason, idempotencyKey);
        payment.addRefund(refund);
        try {
            refunds.saveAndFlush(refund);
        } catch (DataIntegrityViolationException race) {
            // Concurrent insert with the same key: the UNIQUE key is the guard; return the winner.
            log.warn("Concurrent refund insert for idempotencyKey={}; the winning row stands",
                    idempotencyKey);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Refund winner = refunds.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
            Payment winningPayment = payments.findById(winner.getPayment().getId())
                    .orElseThrow(() -> race);
            return winningPayment;
        }
        log.info("Refund requested paymentId={} amount={} reason={} key={}", paymentId, amount, reason,
                idempotencyKey);
        return payment;
    }

    /** Records what the gateway accepted; the refund stays PENDING until its webhook confirms it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund recordRefundOutcome(long refundId, RefundResult result) {
        Refund refund = refunds.findById(refundId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Refund " + refundId + " vanished between request and gateway answer"));
        if (result.status() == RefundStatus.FAILED) {
            refund.markFailed();
            log.info("Refund id={} rejected by the gateway: {}", refundId, result.failureReason());
        } else {
            refund.recordAcceptance(result.gatewayRefundId());
            log.info("Refund id={} accepted by the gateway as {} (provisional — webhook confirms)",
                    refundId, result.gatewayRefundId());
        }
        return refund;
    }

    /** Marks a refund FAILED when the gateway call itself threw; a retry uses a fresh key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Refund markRefundFailed(long refundId, String reason) {
        Refund refund = refunds.findById(refundId)
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Refund " + refundId + " vanished before its failure could be recorded"));
        refund.markFailed();
        log.warn("Refund id={} marked FAILED (gateway call failed: {})", refundId, reason);
        return refund;
    }
}
