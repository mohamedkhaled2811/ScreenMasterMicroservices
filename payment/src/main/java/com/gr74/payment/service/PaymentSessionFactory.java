package com.gr74.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.config.PaymentProps;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.GatewaySelector;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.repository.PaymentAttemptRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens one new gateway checkout session, in the only order that is safe.
 *
 * <p>This is a separate bean from {@link PaymentService} for a specific reason: the attempt row must
 * be <b>committed before the gateway is called</b>, and a {@code @Transactional} method calling
 * another method on {@code this} would not start a new transaction (Spring's proxy is bypassed on
 * self-invocation — the same trap {@code CatalogUpserter} exists to avoid in the catalog service).
 * Putting the committed insert on a different bean makes the boundary real rather than decorative.
 *
 * <p>The ordering matters because a database transaction cannot span an external gateway:
 *
 * <pre>
 *   1. INSERT attempt (PENDING, idempotency key)   -- committed, on its own
 *   2. call the gateway                            -- outside any transaction
 *   3. UPDATE attempt with the session it returned -- committed, on its own
 * </pre>
 *
 * If step 2 times out we do not know whether the gateway created a session — but step 1 already
 * committed, so there is a row to reconcile against, and its idempotency key makes a retry safe. The
 * failure mode is a stranded {@code PENDING} attempt (recoverable) instead of a charge with no local
 * record (not recoverable).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSessionFactory {

    private final PaymentAttemptRepository attempts;
    private final GatewaySelector gatewaySelector;
    private final PaymentProps paymentProps;

    /**
     * Create an attempt and open a session for it.
     *
     * @throws com.gr74.payment.exception.GatewayNotAvailableException  gateway not registered (400)
     * @throws com.gr74.payment.exception.CurrencyNotSupportedException gateway can't settle it (400)
     * @throws com.gr74.payment.gateway.GatewayException                gateway unreachable (503)
     */
    public PaymentAttempt openNewSession(Payment payment, PaymentGatewayType gatewayType, String currency) {
        // Guard 6, before anything is written: a gateway that isn't registered, or can't settle this
        // currency, must be rejected without leaving a dead attempt row behind.
        PaymentGateway gateway = gatewaySelector.select(gatewayType, currency);

        PaymentAttempt attempt = insertPendingAttempt(payment, gatewayType);

        GatewaySession session;
        try {
            session = gateway.createSession(new GatewaySessionRequest(
                    payment.getId(),
                    attempt.getId(),
                    payment.getBookingId(),
                    payment.getUserId(),
                    payment.getAmount(),
                    payment.getCurrency(),
                    attempt.getIdempotencyKey(),
                    paymentProps.returnUrl(attempt.getId()),
                    paymentProps.cancelUrl(attempt.getId())));
        } catch (RuntimeException e) {
            // Deliberately leave the attempt PENDING. We do NOT know the gateway didn't create a
            // session, so marking it FAILED here could contradict a webhook that arrives seconds
            // later. Reconciliation resolves it by asking the gateway what actually happened.
            log.warn("Gateway {} failed to open a session for attempt {}; leaving it PENDING for reconciliation",
                    gatewayType, attempt.getId(), e);
            throw e;
        }

        return recordSession(attempt.getId(), session);
    }

    /**
     * Insert the attempt in its own committed transaction.
     *
     * <p>{@code REQUIRES_NEW} so the row survives independently of whatever the caller is doing — the
     * evidence must outlive a later failure, which is the same reasoning the webhook store uses.
     *
     * <p>A {@link DataIntegrityViolationException} here means the partial unique index
     * ({@code uq_active_attempt_per_payment}) rejected a second live attempt: two "Pay" clicks raced
     * and this one lost. That is a correct outcome, not an error to leak as a 500 — the loser is told
     * to retry, and will then find the winner's live attempt and reuse it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected PaymentAttempt insertPendingAttempt(Payment payment, PaymentGatewayType gatewayType) {
        PaymentAttempt attempt = new PaymentAttempt(gatewayType, newIdempotencyKey(payment));
        payment.addAttempt(attempt);
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
    protected PaymentAttempt recordSession(Long attemptId, GatewaySession session) {
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

    /** Whether an attempt is still usable — exposed for the sweeper and tests. */
    public static boolean isLive(PaymentAttempt attempt, Instant now) {
        return attempt.getStatus() == PaymentAttemptStatus.PENDING && attempt.isLiveAt(now);
    }
}
