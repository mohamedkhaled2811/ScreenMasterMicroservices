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
 * Expires lapsed gateway sessions — the second of the two Phase-3 clocks (BUILD_PLAN 3.4).
 *
 * <p>This sweeper answers "whose gateway session lapsed unused?", a different question from
 * Booking's hold sweeper ("whose 15-minute seat hold ran out?") — hence two independent jobs, not
 * one. An expired attempt is <em>not</em> a payment failure: the payment stays PENDING so the user
 * can Pay Again on the same obligation, which is why this job emits nothing. Re-runs are no-ops
 * through the existing terminal-state guard in
 * {@link PaymentAttempt#transitionTo(PaymentAttemptStatus, String, String)}.
 *
 * <p><b>Reconciliation decides first; blind expiry is only for what the gateway has
 * disowned.</b> Step 3.6's reconciliation sweep probes PENDING attempts stale for longer than
 * {@code payment.reconciliation.stale-after} against the gateway and applies the answer — but this
 * job runs every 60 seconds and would otherwise expire those same attempts long before the
 * 5-minute reconciliation tick ever asks, closing attempts that were in fact paid. So this sweeper
 * <b>skips</b> any lapsed attempt still inside the reconciliation window
 * ({@code expiresAt} newer than {@code now − staleAfter}) and leaves it for reconciliation to
 * adjudicate. Only attempts older than the window — ones the gateway's retry backoff has had every
 * chance to report on — are expired blind.
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

    /**
     * Fire on the configured cadence. Any error escaping a tick is caught here so a single bad
     * run never kills the scheduler thread (the {@code TmdbScheduledTasks} idiom) — lapsed
     * sessions simply wait for the next tick.
     */
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

    /**
     * Expire PENDING attempts past their session deadline that are also older than the
     * reconciliation window. Direct-invocation testable with a frozen clock; returns how many
     * sessions were actually closed.
     */
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
