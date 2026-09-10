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
 * The refund path's committed writes — <b>two transactions with the gateway call between them</b>.
 *
 * <p>The ordering is the invariant: tx 1 takes a {@code PESSIMISTIC_WRITE} row lock on the payment,
 * validates {@code Σ(SUCCEEDED + PENDING refunds) ≤ payment.amount}, and inserts the refund
 * {@code PENDING}; the gateway is then called with <b>no lock held</b>; tx 2 records what the
 * gateway accepted. Never hold a row lock across network I/O — the same rule
 * {@link PaymentSessionFactory} follows for checkout sessions.
 *
 * <p>A separate bean because a self-invoked {@code @Transactional} method is not transactional at
 * all (Spring's proxy is bypassed), so {@link RefundService} must <em>inject</em> these boundaries
 * for them to exist. Split out of {@code PaymentWriter} when Phase 3 left that class serving four
 * unrelated callers; the semantics are unchanged by the move.
 *
 * <p>Note that tx 2 is deliberately <em>provisional</em>: the gateway's API response is not the
 * final word, so a refund stays {@code PENDING} until its webhook arrives and
 * {@link WebhookWriter#markRefundReceived} promotes it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundWriter {

    private final PaymentRepository payments;
    private final RefundRepository refunds;

    /**
     * tx 1 of the refund: lock the payment, validate the invariant, insert the refund PENDING.
     *
     * <p>The {@code PESSIMISTIC_WRITE} lock serializes concurrent refunds against one payment: the
     * check ({@code SUM(SUCCEEDED + PENDING) + requested <= amount}) and the insert happen while no
     * other refund for this payment can interleave. {@code PENDING} counts deliberately — an
     * in-flight refund whose outcome is still unknown blocks a second one, because conservative on
     * money is correct while an outcome is in flight. Only {@code FAILED} refunds drop out of the
     * sum: the gateway rejected them, so they constrain nothing.
     *
     * @param requested null means "the full remaining capacity" (what the admin endpoint and the
     *                  auto-refund ask for when no amount is given)
     * @return the payment as it stands after the insert (detached — the transaction committed)
     * @throws PaymentNotFoundException      no such payment (404)
     * @throws PaymentException              {@code PAYMENT_NOT_REFUNDABLE} when the payment was never
     *                                       captured, {@code PAYMENT_REFUND_EXCEEDS_REMAINING} when
     *                                       the invariant would break, {@code PAYMENT_VALIDATION_ERROR}
     *                                       for a non-positive amount (both 409s and the 400 above
     *                                       are the already-defined codes — none are invented here)
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
            // "Refund everything left" when nothing is left is not a malformed request — it is a
            // refund past capacity, so it reports EXCEEDS like any other over-capacity request.
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
        // compareTo, not equals: 500.0 and 500.00 are equal in value but not as BigDecimals —
        // the one place a rounding-shaped bug would be unforgivable is refund math.
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
            // Same key inserted concurrently (a redelivered rejection racing the first): the UNIQUE
            // key — not a read — is the real guard. The caller re-reads the winner's row by key
            // and returns it, so the second delivery collapses onto the first answer.
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

    /**
     * tx 2 of the refund: record what the gateway ACCEPTED — and leave the refund PENDING.
     *
     * <p>The gateway's API response is provisional, not final: even a synchronous-looking
     * "succeeded" answer has not demonstrably moved money until the refund webhook confirms it
     * (see {@link #markRefundReceived}). So this transaction records the gateway's refund id and
     * changes nothing else. Only an outright rejection ({@code FAILED}) is terminal here — and a
     * {@code FAILED} refund drops out of the invariant sum, so retrying under a fresh key is safe.
     */
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

    /**
     * tx 2's failure branch: the gateway call itself threw (timeout, 5xx), so no trustworthy
     * answer exists. The refund is marked FAILED — a timeout leaves it PENDING only until this
     * runs, and a retry then creates a fresh refund under a fresh key rather than inheriting an
     * unknown outcome.
     */
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
