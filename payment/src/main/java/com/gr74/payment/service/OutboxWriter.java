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
 * Claims unpublished outbox rows and marks them published only after the broker accepts them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxMessageRepository outbox;

    /** Claims up to {@code limit} unpublished rows in its own short transaction. */
    @Transactional
    public List<OutboxMessage> claimBatch(int limit) {
        return outbox.claimPending(limit);
    }

    /** Marks a row published; called only after the broker accepts it (at-least-once delivery). */
    @Transactional
    public void markPublished(OutboxMessage message) {
        OutboxMessage managed = outbox.findById(message.getId())
                .orElseThrow(() -> new PaymentException(PaymentErrorCode.PAYMENT_INTERNAL_ERROR,
                        "Outbox row " + message.getId() + " vanished between claim and mark"));
        managed.markPublished();
    }
}
