package com.gr74.payment.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.client.BookingPayability;
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
 * Every committed write on the checkout path, each in its own transaction.
 *
 * <p>This is a separate bean for one specific reason: the checkout path needs writes that are
 * <b>committed before the gateway is called</b>, and a {@code @Transactional} method invoked on
 * {@code this} is not transactional at all — Spring's proxy is bypassed on self-invocation (the same
 * trap {@code CatalogUpserter} exists to avoid in the catalog service). When these methods lived on
 * {@link PaymentService} and {@link PaymentSessionFactory}, each was called from the same bean that
 * declared it, so every {@code REQUIRES_NEW} was inert: the writes happened to commit in the right
 * order only because no caller had an ambient transaction. Putting them on a third bean that both
 * callers <em>inject</em> makes every proxy hop — and therefore every boundary — real.
 *
 * <p>All three methods are {@code REQUIRES_NEW} for the same reason: a database transaction cannot
 * span an external gateway, so the obligation row and the attempt row must each be committed
 * independently, <b>before</b> {@link PaymentSessionFactory} calls the gateway. The attempt's FK to
 * {@code payments(id)} is what forces the obligation write to be here too: if the payment insert
 * joined a caller's still-open transaction, a {@code REQUIRES_NEW} attempt insert would violate it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWriter {

    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;

    /**
     * The obligation for a booking, created on first sight — committed on its own.
     *
     * <p>Read-then-insert with the {@code UNIQUE booking_id} constraint as the real guard: two
     * concurrent first-time requests both miss the read, both insert, and the loser catches the
     * violation and re-reads the winner's row. The constraint — not the read — is what guarantees one
     * obligation per booking. (Same shape as the old idempotency-key guard, applied to a better key.)
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
     * Insert the attempt in its own committed transaction.
     *
     * <p>{@code REQUIRES_NEW} so the row survives independently of whatever the caller is doing — the
     * evidence must outlive a later failure, which is the same reasoning the webhook store uses.
     *
     * <p>The caller's {@code Payment} is <b>detached</b>: it came out of a different, already-committed
     * transaction ({@link #getOrCreatePayment}), so its lazy {@code attempts} collection is bound to a
     * session that no longer exists. Mutating it here threw {@code LazyInitializationException} on the
     * "Pay Again" path — a freshly <em>inserted</em> payment carries a plain {@code ArrayList} (works by
     * luck), a <em>loaded</em> one carries an uninitialized proxy (fails). So this transaction loads its
     * own managed copy and wires the attempt there — the same rule {@link #recordSession} follows: a
     * write trusts only rows it loaded itself.
     *
     * <p>A {@link DataIntegrityViolationException} here means the partial unique index
     * ({@code uq_active_attempt_per_payment}) rejected a second live attempt: two "Pay" clicks raced
     * and this one lost. That is a correct outcome, not an error to leak as a 500 — the loser is told
     * to retry, and will then find the winner's live attempt and reuse it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentAttempt insertPendingAttempt(Payment payment, PaymentGatewayType gatewayType) {
        Payment managed = payments.findById(payment.getId()).orElseThrow(
                () -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Payment " + payment.getId() + " vanished before its attempt could be inserted"));
        PaymentAttempt attempt = new PaymentAttempt(gatewayType, newIdempotencyKey(managed));
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

    /** Save what the gateway returned, in its own committed transaction. */
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

    /**
     * The key forwarded to the gateway. Includes the payment id and a random component: it must be
     * unique per <em>attempt</em> (a deliberate retry after a lapsed session is a genuinely new
     * charge attempt and must not be deduped against the old one), while still being the token that
     * makes a <em>transport-level</em> retry of one attempt safe.
     */
    private String newIdempotencyKey(Payment payment) {
        return "pay-" + payment.getId() + "-" + UUID.randomUUID();
    }
}
