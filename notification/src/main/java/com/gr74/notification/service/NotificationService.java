package com.gr74.notification.service;

import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.gr74.notification.messaging.BookingConfirmationRejectedEvent;
import com.gr74.notification.messaging.BookingConfirmedEvent;
import com.gr74.notification.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The idempotent consumer (BUILD_PLAN 4.3): claims an event by inserting its id, and only then
 * "sends" the email — which is a structured log line, standing in for a real email provider.
 *
 * <p><b>Why the claim-first ordering is the whole point.</b> RabbitMQ delivers at-least-once: a
 * consumer crash before the ack, a broker restart, or the {@code --scale notification=2} demo can all
 * deliver the same event twice. So the send must be a no-op on the second delivery. The guard is the
 * {@code processed_events} INSERT, not a check: we insert the row first and treat a
 * {@link DataIntegrityViolationException} AS the dedupe (a duplicate claim → ack-and-no-op, the
 * {@code WebhookWriter#storeVerifiedEvent} idiom). There is deliberately no {@code existsById(...)}
 * before the insert — that read-then-insert has a window where two concurrent consumer instances both
 * pass the check and both send, which is exactly the double-send this phase exists to prove
 * impossible.
 *
 * <p><b>The honest residual.</b> This is at-least-once with an idempotent consumer, i.e. exactly-once
 * <em>in effect</em> — the only exactly-once that exists across a broker. One gap remains: a crash
 * after the send but before the transaction commits rolls back the claim and the redelivery sends
 * once more. Against a real email provider you would close even that by passing {@code eventId} as
 * the provider's own idempotency key (e.g. the SendGrid/SES message id), so the provider itself
 * collapses the duplicate. For a log line it is free; we do not claim more than we have.
 *
 * <p>A plain {@code @Transactional} service — deliberately NOT the {@code @RabbitListener} itself —
 * so it is unit-testable by direct invocation without a broker (the {@code BookingConfirmer} /
 * {@code MovieProjector} idiom). The listener is a thin shell over it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** The {@code event_type} written onto a {@code BookingConfirmed} dedupe row. */
    public static final String BOOKING_CONFIRMED = "BOOKING_CONFIRMED";

    /** The {@code event_type} written onto a {@code BookingConfirmationRejected} dedupe row. */
    public static final String BOOKING_CONFIRMATION_REJECTED = "BOOKING_CONFIRMATION_REJECTED";

    private final ProcessedEventRepository processedEvents;

    @Transactional
    public boolean processBookingConfirmed(BookingConfirmedEvent event) {
        if (!claim(event.eventId(), BOOKING_CONFIRMED)) {
            return false;
        }
        log.info("Sending notification: booking confirmed, ticket emailed — bookingReference={} userId={}",
                event.bookingReference(), event.userId());
        return true;
    }

    @Transactional
    public boolean processBookingConfirmationRejected(BookingConfirmationRejectedEvent event) {
        if (!claim(event.eventId(), BOOKING_CONFIRMATION_REJECTED)) {
            return false;
        }
        log.info("Sending notification: payment arrived too late, you have been refunded — "
                        + "bookingReference={} paymentId={} reason={}",
                event.bookingReference(), event.paymentId(), event.reason());
        return true;
    }

    /**
     * THE CLAIM: try to insert the dedupe row. {@code true} means this consumer won the event and may
     * send; {@code false} means a duplicate/redelivery — someone already processed it, so the caller
     * returns without sending.
     *
     * <p>The repository's {@code claim} is a raw INSERT (see its javadoc for why not
     * {@code saveAndFlush}): it either creates the row or violates the PK. On the duplicate the
     * failed statement leaves the transaction in a state we do not want to commit, so we mark it
     * rollback-only explicitly rather than letting Spring commit over a failed insert. Rolling back
     * cleanly is what lets the listener ack the redelivery as handled.
     */
    private boolean claim(long eventId, String eventType) {
        try {
            processedEvents.claim(eventId, eventType, Instant.now());
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            // The dedupe fired: a concurrent (or redelivered) claim won. Ack-and-no-op — do NOT rethrow.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("Duplicate booking event eventId={} eventType={} — already processed, not sending again",
                    eventId, eventType);
            return false;
        }
    }
}