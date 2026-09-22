package com.gr74.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.config.ReconciliationProps;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.repository.PaymentAttemptRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Expires lapsed gateway sessions past the reconciliation window; the payment stays PENDING for Pay Again.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.sweeps", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class AttemptExpirySweeper {

    private final PaymentAttemptRepository attempts;
    private final Clock clock;
    private final ReconciliationProps reconciliation;

    /** Runs on a fixed delay; failures are logged so the next tick can retry. */
    @Scheduled(fixedDelayString = "${payment.expiry.sweep-interval-millis:60000}")
    public void tick() {
        try {
            int expired = expireLapsedAttempts(clock.instant());
            if (expired > 0) {
                log.info("Attempt expiry sweep expired {} lapsed session(s)", expired);
            } else {
                log.debug("Attempt expiry sweep: nothing to expire");
            }
        } catch (RuntimeException e) {
            log.error("Attempt expiry sweep tick failed (next tick retries): {}", e.getMessage(), e);
        }
    }

    /** Expires PENDING attempts past their deadline and older than the reconciliation window. */
    @Transactional
    public int expireLapsedAttempts(Instant now) {
        Instant reconciliationCutoff = now.minus(reconciliation.staleAfter());
        List<PaymentAttempt> lapsed = attempts.findLapsed(PaymentAttemptStatus.PENDING, now);
        int expired = 0;
        for (PaymentAttempt attempt : lapsed) {
            if (!attempt.getExpiresAt().isBefore(reconciliationCutoff)) {
                log.debug("Attempt id={} lapsed but inside the reconciliation window (expiresAt={}) — "
                                + "left PENDING for reconciliation",
                        attempt.getId(), attempt.getExpiresAt());
                continue;
            }
            if (attempt.transitionTo(PaymentAttemptStatus.EXPIRED, null, null)) {
                attempts.save(attempt);
                expired++;
                log.info("Expired attempt id={} paymentId={} (session lapsed, past reconciliation window) — "
                                + "payment stays PENDING for Pay Again",
                        attempt.getId(), attempt.getPayment().getId());
            }
        }
        return expired;
    }
}
