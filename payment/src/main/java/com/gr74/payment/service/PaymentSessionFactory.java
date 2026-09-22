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
 * Opens one gateway checkout session: insert attempt, call gateway, record session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentSessionFactory {

    private final PaymentWriter paymentWriter;
    private final GatewaySelector gatewaySelector;
    private final PaymentProps paymentProps;

    /**
     * Creates an attempt and opens a session for it.
     *
     * @throws com.gr74.payment.exception.GatewayNotAvailableException gateway not registered (400)
     * @throws com.gr74.payment.exception.CurrencyNotSupportedException gateway can't settle it (400)
     * @throws com.gr74.payment.gateway.GatewayException gateway unreachable (503)
     */
    @Observed(name = "payment.session.open", contextualName = "open-session")
    public PaymentAttempt openNewSession(Payment payment, PaymentGatewayType gatewayType, String currency) {
        // Reject unregistered gateways and unsupported currencies before writing anything.
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
            // Leave the attempt PENDING; reconciliation resolves whether a session was created.
            log.warn("Gateway {} failed to open a session for attempt {}; leaving it PENDING for reconciliation",
                    gatewayType, attempt.getId(), e);
            throw e;
        }

        return paymentWriter.recordSession(attempt.getId(), session);
    }

    /**
     * Retries an unanswered gateway call on its existing attempt row with the original idempotency key.
     *
     * @throws com.gr74.payment.exception.GatewayNotAvailableException gateway not registered (400)
     * @throws com.gr74.payment.exception.CurrencyNotSupportedException gateway can't settle it (400)
     * @throws com.gr74.payment.gateway.GatewayException gateway unreachable (503)
     */
    public PaymentAttempt completeStrandedSession(Long attemptId, PaymentGatewayType gatewayType,
            String currency) {
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
            // Leave the row PENDING for the next retry.
            log.warn("Gateway {} failed to complete stranded attempt {}; leaving it PENDING for retry",
                    gatewayType, attemptId, e);
            throw e;
        }

        return paymentWriter.recordSession(retry.attemptId(), session);
    }

    /** Returns whether an attempt is still usable. */
    public static boolean isLive(PaymentAttempt attempt, Instant now) {
        return attempt.getStatus() == PaymentAttemptStatus.PENDING && attempt.isLiveAt(now);
    }
}
