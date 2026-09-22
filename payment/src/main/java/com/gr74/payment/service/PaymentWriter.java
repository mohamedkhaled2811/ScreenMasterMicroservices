package com.gr74.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.client.BookingPayability;
import com.gr74.payment.config.AttemptProps;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Committed writes on the checkout path, each in its own transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWriter {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final AttemptProps attemptProps;
    private final Clock clock;

    /**
     * Gets or creates the payment obligation for a booking in its own transaction.
     * Concurrent inserts collapse onto the winner via the UNIQUE booking_id constraint.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Payment getOrCreatePayment(BookingPayability booking) {
        return payments.findByBookingId(booking.bookingId())
                .orElseGet(() -> insertPayment(booking));
    }

    private Payment insertPayment(BookingPayability booking) {
        Payment payment = new Payment(
                booking.bookingId(),
                booking.userId(),
                booking.totalAmount(),   // authoritative: from Booking, never the client
                booking.currency());
        try {
            Payment saved = payments.saveAndFlush(payment);
            log.info("Created payment id={} bookingId={} amount={} {}",
                    saved.getId(), saved.getBookingId(), saved.getAmount(), saved.getCurrency());
            return saved;
        } catch (DataIntegrityViolationException race) {
            log.warn("Concurrent create for bookingId={}; returning the winning row", booking.bookingId());
            return payments.findByBookingId(booking.bookingId()).orElseThrow(() -> race);
        }
    }

    /**
     * Inserts a PENDING attempt in its own transaction; loads its own managed payment copy.
     * A unique-violation here means a concurrent insert won and the caller should retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt insertPendingAttempt(Payment payment, PaymentGatewayType gatewayType) {
        Payment managed = payments.findById(payment.getId()).orElseThrow(
                () -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment " + payment.getId() + " vanished before its attempt could be inserted"));
        // Provisional deadline until the gateway answers; replaced by the real one in recordSession.
        PaymentAttempt attempt = new PaymentAttempt(gatewayType, newIdempotencyKey(managed),
                clock.instant().plus(attemptProps.strandedTtl()));
        managed.addAttempt(attempt);
        try {
            PaymentAttempt saved = attempts.saveAndFlush(attempt);
            log.info("Created attempt id={} paymentId={} gateway={}",
                    saved.getId(), payment.getId(), gatewayType);
            return saved;
        } catch (DataIntegrityViolationException race) {
            log.warn("Concurrent attempt creation for paymentId={} — another live attempt won",
                    payment.getId(), race);
            throw new PaymentException(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                    "A payment session for this booking is already being created; retry to reuse it",
                    race);
        }
    }

    /**
     * Prepares a stranded-attempt retry in one transaction, reusing the row's original idempotency key.
     *
     * @return the retry facts, detached and safe to use outside any transaction
     * @throws PaymentException {@code PAYMENT_VALIDATION_ERROR} if the attempt is no longer stranded
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StrandedRetry prepareStrandedRetry(Long attemptId, PaymentGatewayType gatewayType) {
        PaymentAttempt managed = attempts.findById(attemptId).orElseThrow(
                () -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Attempt " + attemptId + " vanished before its retry could be prepared"));
        if (!managed.isStranded()) {
            throw new PaymentException(PaymentErrorCode.PAYMENT_VALIDATION_ERROR,
                    "A payment session for this booking is already being created; retry to reuse it");
        }
        managed.adoptGateway(gatewayType);
        Payment payment = managed.getPayment();
        return new StrandedRetry(managed.getId(), payment.getId(), payment.getBookingId(),
                payment.getUserId(), payment.getAmount(), payment.getCurrency(),
                managed.getIdempotencyKey());
    }

    /** Saves what the gateway returned, in its own committed transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt recordSession(Long attemptId, GatewaySession session) {
        PaymentAttempt attempt = attempts.findById(attemptId).orElseThrow(
                () -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Attempt " + attemptId + " vanished between creation and session recording"));
        attempt.recordSession(session.gatewaySessionId(), session.checkoutUrl(), session.expiresAt());
        PaymentAttempt saved = attempts.saveAndFlush(attempt);
        log.info("Attempt id={} now has session {} expiring {}",
                saved.getId(), saved.getGatewaySessionId(), saved.getExpiresAt());
        return saved;
    }

    /** Builds a per-attempt idempotency key for the gateway call. */
    private String newIdempotencyKey(Payment payment) {
        return "pay-" + payment.getId() + "-" + UUID.randomUUID();
    }

    /** Facts a stranded-attempt retry needs, detached from any persistence context. */
    public record StrandedRetry(
            Long attemptId,
            Long paymentId,
            Long bookingId,
            String userId,
            BigDecimal amount,
            String currency,
            String idempotencyKey) {
    }
}
