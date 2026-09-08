package com.gr74.payment.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.outbox.OutboxMessage;
import com.gr74.payment.outbox.OutboxMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The relay's two short transactions: claim unpublished rows, then mark them — <b>only after the
 * broker has accepted them</b>.
 *
 * <p>Both are deliberately tiny and separate. {@code claimBatch} commits <em>before</em> the
 * publish so no lock is held across the broker call; {@code markPublished} runs after, which is
 * what makes delivery at-least-once: a crash between the two re-publishes on the next tick, and
 * the consumers' guards turn that into exactly-once in effect. Mark-then-publish would instead
 * silently lose a {@code PaymentSucceeded} — a customer charged with no booking.
 *
 * <p>A separate bean so {@link com.gr74.payment.outbox.OutboxRelay} injects real proxy boundaries
 * rather than self-invoking inert ones. Split out of {@code PaymentWriter}; semantics unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxMessageRepository outbox;

    /**
     * Claim up to {@code limit} unpublished rows for this tick. Runs in its own short transaction:
     * the native {@code SELECT ... FOR UPDATE SKIP LOCKED} needs one, and it must commit promptly
     * so the row locks are not held across the broker publish that follows.
     */
    @Transactional
    public List<OutboxMessage> claimBatch(int limit) {
        return outbox.claimPending(limit);
    }

    /**
     * Mark a row published — called only AFTER the broker accepted it, never before. That ordering
     * is the at-least-once guarantee: a crash between publish and mark re-publishes (safe, the
     * consumer is idempotent), while mark-then-publish could lose the event outright.
     */
    @Transactional
    public void markPublished(OutboxMessage message) {
        OutboxMessage managed = outbox.findById(message.getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Outbox row " + message.getId() + " vanished between claim and mark"));
        managed.markPublished();
    }
}
