package com.gr74.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gr74.payment.config.ReconciliationProps;
import com.gr74.payment.gateway.GatewayEvent;
import com.gr74.payment.gateway.GatewayPaymentStatus;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.gateway.GatewayStatusQuery;
import com.gr74.payment.gateway.PaymentGateway;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.repository.PaymentAttemptRepository;
import com.gr74.payment.webhook.WebhookProcessor;
import com.gr74.payment.webhook.WebhookResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconciliation — what makes the webhook path <em>recoverable</em> rather than critical
 * (BUILD_PLAN 3.6).
 *
 * <p>Sweeps attempts stuck {@code PENDING} past {@code payment.reconciliation.stale-after}
 * (default 10 minutes — the SAME property step 3.4's {@code AttemptExpirySweeper} reads, so blind
 * expiry never wins the race against this gateway check), asks the gateway what it believes, and
 * funnels the answer <b>through the exact webhook handler</b> — a synthetic {@code GatewayEvent}
 * whose id is {@code "recon:{attemptId}:{status}"}, stored through the same evidence insert and
 * applied by the same {@code applyOutcome}. The {@code UNIQUE (gateway, event_id)} insert makes a
 * late real webhook, a second recon pass, and the original webhook all collapse onto ONE applied
 * outcome. If a second state-transition path ever appears beside this one, that is the bug.
 *
 * <p>The threshold exists to clear a real gateway's webhook retry backoff: anything younger may
 * still have a delivery in flight, and probing it would race the webhook rather than recover from
 * it.
 *
 * <p>When the gateway itself cannot answer ({@code fetchStatus} throws), the per-item catch logs
 * and skips — the attempt stays PENDING and the next sweep retries it. There is deliberately
 * <b>no give-up counter</b>: an attempt the gateway cannot answer for is eventually closed by the
 * expires-past branch below, so nothing is probed forever.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {

    private final PaymentAttemptRepository attempts;
    private final WebhookProcessor processor;
    private final GatewayRegistry registry;
    private final ReconciliationProps reconciliation;
    private final Clock clock;

    /**
     * Fire on the configured cadence. Any error escaping a tick is caught here so a single bad
     * run never kills the scheduler thread (the {@code TmdbScheduledTasks} idiom) — stale
     * attempts simply wait for the next tick.
     */
    @Scheduled(fixedDelayString = "${payment.reconciliation.sweep-interval-millis:300000}")
    public void tick() {
        try {
            int recovered = reconcile(clock.instant());
            if (recovered > 0) {
                log.info("Reconciliation sweep recovered {} stale attempt(s)", recovered);
            } else {
                log.debug("Reconciliation sweep: nothing to recover");
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation sweep tick failed (next tick retries): {}", e.getMessage(), e);
        }
    }

    /**
     * Probe stale PENDING attempts against their gateways. Direct-invocation testable with a
     * frozen instant; returns how many attempts reached a decided outcome through the webhook
     * handler. One bad attempt never kills the run: the per-item catch skips it for the next
     * sweep.
     */
    public int reconcile(Instant now) {
        Instant cutoff = now.minus(reconciliation.staleAfter());
        List<PaymentAttempt> stale = attempts.findStale(PaymentAttemptStatus.PENDING, cutoff);
        // Bounded: the first tick after a long outage walks the backlog over several passes
        // instead of one enormous loop (same reasoning as the outbox relay's batch size).
        List<PaymentAttempt> batch = stale.subList(0, Math.min(stale.size(), reconciliation.batchSize()));
        int recovered = 0;
        for (PaymentAttempt attempt : batch) {
            try {
                if (reconcileOne(attempt, now)) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                // fetchStatus threw (or the registry has no such gateway): leave PENDING, retry
                // next sweep. Nothing is probed forever — see the class javadoc.
                log.warn("Reconciliation skipped attempt id={} ({}); next sweep retries",
                        attempt.getId(), e.getMessage());
            }
        }
        return recovered;
    }

    /**
     * Ask the gateway about one attempt and apply its answer through the webhook handler.
     *
     * <ul>
     *   <li>Gateway says {@code SUCCEEDED}/{@code FAILED} → applied as that outcome (payment
     *       promoted, outbox written — exactly as if the webhook had arrived).</li>
     *   <li>Gateway says {@code PENDING} (it never heard of this session — the user never paid)
     *       → applied as {@code EXPIRED}, but ONLY past the session deadline. A stale-but-live
     *       session may still be paid, so it stays PENDING for a later sweep.</li>
     * </ul>
     */
    private boolean reconcileOne(PaymentAttempt attempt, Instant now) {
        PaymentGateway gateway = registry.require(attempt.getGateway());
        GatewayPaymentStatus answer = gateway.fetchStatus(GatewayStatusQuery.of(
                attempt.getGatewaySessionId(), attempt.getGatewayPaymentId()));

        PaymentAttemptStatus status = answer.status();
        if (status == PaymentAttemptStatus.PENDING) {
            if (attempt.getExpiresAt() == null || !attempt.getExpiresAt().isBefore(now)) {
                log.debug("Attempt id={} stale but its session is still live — left PENDING",
                        attempt.getId());
                return false;
            }
            // The gateway disowned it (never paid) and the session lapsed: close the attempt.
            // The payment stays PENDING for Pay Again — same meaning as the expiry sweeper's flip.
            status = PaymentAttemptStatus.EXPIRED;
        }

        GatewayEvent synthetic = new GatewayEvent(
                "recon:" + attempt.getId() + ":" + status,
                "reconciliation",
                attempt.getGatewaySessionId(),
                answer.gatewayPaymentId() != null
                        ? answer.gatewayPaymentId()
                        : attempt.getGatewayPaymentId(),
                status,
                answer.failureReason());
        WebhookResult result = processor.applyReconciled(attempt.getGateway(), synthetic);
        log.info("Reconciled attempt id={} as {} (gateway said {}): {}",
                attempt.getId(), status, answer.status(), result.detail());
        return result.outcome() == WebhookResult.Outcome.PROCESSED;
    }
}
