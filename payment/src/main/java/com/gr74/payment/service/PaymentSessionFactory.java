package com.gr74.payment.service;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.gr74.payment.config.PaymentProps;
import com.gr74.payment.gateway.GatewaySession;
import com.gr74.payment.gateway.GatewaySessionRequest;
import com.gr74.payment.gateway.GatewaySelector;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Opens one new gateway checkout session, in the only order that is safe.
 *
 * <p>This bean orchestrates the <em>conversation</em> with the gateway; every database write around
 * it is delegated to {@link PaymentWriter}, a separate bean. That separation is what makes the
 * transaction boundaries real: a {@code @Transactional} method invoked on {@code this} is not
 * transactional at all (Spring's proxy is bypassed on self-invocation — the same trap
 * {@code CatalogUpserter} exists to avoid in the catalog service), and an earlier version of this
 * class declared its writes {@code REQUIRES_NEW} and then called them on {@code this}, silently
 * inert. Delegating to an injected bean makes every proxy hop — and therefore every boundary —
 * real.
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

    private final PaymentWriter paymentWriter;
    private final GatewaySelector gatewaySelector;
    private final PaymentProps paymentProps;

    /**
     * Create an attempt and open a session for it.
     *
     * @throws com.gr74.payment.exception.GatewayNotAvailableException  gateway not registered (400)
     * @throws com.gr74.payment.exception.CurrencyNotSupportedException gateway can't settle it (400)
     * @throws com.gr74.payment.gateway.GatewayException                gateway unreachable (503)
     *
     * <p>{@code @Observed} (Phase 6, decision D2): one span per opened session, so the waterfall
     * distinguishes "our own seat query" from "the gateway call" — the two calls this method makes.
     */
    @Observed(name = "payment.session.open", contextualName = "open-session")
    public PaymentAttempt openNewSession(Payment payment, PaymentGatewayType gatewayType, String currency) {        // Guard 6, before anything is written: a gateway that isn't registered, or can't settle this
        // currency, must be rejected without leaving a dead attempt row behind.
        PaymentGateway gateway = gatewaySelector.select(gatewayType, currency);

        PaymentAttempt attempt = paymentWriter.insertPendingAttempt(payment, gatewayType);

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

        return paymentWriter.recordSession(attempt.getId(), session);
    }

    /**
     * Retry an unanswered call on its existing attempt row.
     *
     * <p>The mirror of {@link #openNewSession} for the second-and-later {@code POST /payments}: the
     * attempt row already exists (inserted by the first call, committed before the gateway threw),
     * so no new row is inserted — the one-live-attempt index would reject it. The gateway is called
     * with the row's <em>original</em> idempotency key, which is what makes the re-call
     * non-duplicating, and the requested gateway is adopted on the row first, so a retry may switch
     * gateways mid-outage. On failure the attempt stays {@code PENDING} for the next retry (or, past
     * its provisional deadline, for the expiry sweeper); on success the gateway's real session —
     * including its real deadline — is recorded, replacing the provisional one.
     *
     * @throws com.gr74.payment.exception.GatewayNotAvailableException  gateway not registered (400)
     * @throws com.gr74.payment.exception.CurrencyNotSupportedException gateway can't settle it (400)
     * @throws com.gr74.payment.gateway.GatewayException                gateway unreachable (503)
     */
    public PaymentAttempt completeStrandedSession(Long attemptId, PaymentGatewayType gatewayType,
            String currency) {
        // Guard 6, before anything is written — same position as in openNewSession.
        PaymentGateway gateway = gatewaySelector.select(gatewayType, currency);

        PaymentWriter.StrandedRetry retry = paymentWriter.prepareStrandedRetry(attemptId, gatewayType);

        GatewaySession session;
        try {
            session = gateway.createSession(new GatewaySessionRequest(
                    retry.paymentId(),
                    retry.attemptId(),
                    retry.bookingId(),
                    retry.userId(),
                    retry.amount(),
                    retry.currency(),
                    retry.idempotencyKey(),
                    paymentProps.returnUrl(retry.attemptId()),
                    paymentProps.cancelUrl(retry.attemptId())));
        } catch (RuntimeException e) {
            // Same reasoning as openNewSession: we do not know the gateway didn't create a session,
            // so the row stays PENDING — for the next retry, which reuses this same row and key.
            log.warn("Gateway {} failed to complete stranded attempt {}; leaving it PENDING for retry",
                    gatewayType, attemptId, e);
            throw e;
        }

        return paymentWriter.recordSession(retry.attemptId(), session);
    }

    /** Whether an attempt is still usable — exposed for the sweeper and tests. */
    public static boolean isLive(PaymentAttempt attempt, Instant now) {
        return attempt.getStatus() == PaymentAttemptStatus.PENDING && attempt.isLiveAt(now);
    }
}
