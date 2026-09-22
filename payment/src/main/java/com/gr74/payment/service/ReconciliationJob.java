package com.gr74.payment.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * Probes stale PENDING attempts against the gateway and applies the answer via the webhook path.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "payment.sweeps", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationJob {

    private final PaymentAttemptRepository attempts;
    private final WebhookProcessor processor;
    private final GatewayRegistry registry;
    private final ReconciliationProps reconciliation;
    private final Clock clock;

    /** Runs on a fixed delay; failures are logged so the next tick can retry. */
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

    /** Probes stale PENDING attempts; one bad attempt never stops the run. */
    public int reconcile(Instant now) {
        Instant cutoff = now.minus(reconciliation.staleAfter());
        List<PaymentAttempt> stale = attempts.findStale(PaymentAttemptStatus.PENDING, cutoff);
        // Bounded batch so a post-outage backlog drains over several passes.
        List<PaymentAttempt> batch = stale.subList(0, Math.min(stale.size(), reconciliation.batchSize()));
        int recovered = 0;
        for (PaymentAttempt attempt : batch) {
            try {
                if (reconcileOne(attempt, now)) {
                    recovered++;
                }
            } catch (RuntimeException e) {
                // Leave PENDING; the next sweep retries.
                log.warn("Reconciliation skipped attempt id={} ({}); next sweep retries",
                        attempt.getId(), e.getMessage());
            }
        }
        return recovered;
    }

    /** Asks the gateway about one attempt and applies its answer through the webhook handler. */
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
            // Gateway disowned it and the session lapsed; the payment stays PENDING for Pay Again.
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
