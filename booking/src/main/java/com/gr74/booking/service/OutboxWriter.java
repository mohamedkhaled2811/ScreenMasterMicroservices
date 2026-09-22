package com.gr74.booking.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.outbox.OutboxMessage;
import com.gr74.booking.outbox.OutboxMessageRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Claims unpublished outbox rows, then marks them only after the broker accepts them
 * (at-least-once delivery). Separate bean so the relay gets real proxy boundaries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxMessageRepository outbox;

    /**
     * Claim up to {@code limit} unpublished rows. Commits promptly so row locks
     * are not held across the broker publish.
     */
    @Transactional
    public List<OutboxMessage> claimBatch(int limit) {
        return outbox.claimPending(limit);
    }

    /** Mark a row published; called only after the broker accepted it, never before. */
    @Transactional
    public void markPublished(OutboxMessage message) {
        OutboxMessage managed = outbox.findById(message.getId())
                .orElseThrow(() -> new BookingException(BookingErrorCode.BOOKING_INTERNAL_ERROR,
                        "Outbox row " + message.getId() + " vanished between claim and mark"));
        managed.markPublished();
    }
}