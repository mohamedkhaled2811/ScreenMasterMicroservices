package com.gr74.booking.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gr74.booking.config.RabbitConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends Booking's saga events to RabbitMQ — the broker half of the publisher, split out from
 * {@link com.gr74.booking.service.BookingConfirmer} on purpose (the {@code MovieEventPublisher}
 * idiom in Catalog).
 *
 * <p>An {@code @TransactionalEventListener(AFTER_COMMIT)} so the event fires <b>if and only
 * if</b> the confirm row is durably written: publishing inside the confirm transaction would
 * announce a confirmation that a rollback then un-happened. A failure to publish is logged and
 * swallowed — it must not fail the already-committed confirm.
 *
 * <p><b>The gap, named honestly rather than hidden.</b> Booking publishes direct (no outbox until
 * Phase 4), so a broker outage at AFTER_COMMIT loses the event — and the two events are not
 * equally losable. A lost {@code BookingConfirmed} self-heals: nothing downstream has acted yet,
 * the booking still reads CONFIRMED, and Notification's Phase-4 backfill reconciles it. A lost
 * {@code BookingConfirmationRejected}, though, <b>strands a customer's refund</b>: Payment never
 * learns the money arrived too late, and nothing retries it. Booking carries that exposure for
 * exactly one phase, deliberately, sitting next to Payment's outbox as the contrast that motivates
 * Phase 4 — where this publisher is replaced by the same publish-then-mark relay Payment already
 * runs, and the routing keys below do not change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingConfirmed event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE, RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, event);
            log.debug("Published BookingConfirmed bookingId={} ref={} eventId={}",
                    event.bookingId(), event.bookingReference(), event.eventId());
        } catch (RuntimeException e) {
            // The confirm already committed — don't let a broker hiccup fail it. The lost
            // notification self-heals (Phase-4 backfill); the outbox (Phase 4) removes the gap.
            log.warn("Failed to publish BookingConfirmed bookingId={}; event dropped (self-heals): {}",
                    event.bookingId(), e.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmationRejected(BookingConfirmationRejected event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE, RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY, event);
            log.debug("Published BookingConfirmationRejected bookingId={} reason={} paymentId={} eventId={}",
                    event.bookingId(), event.reason(), event.paymentId(), event.eventId());
        } catch (RuntimeException e) {
            // The rejection already committed — but unlike the confirmed path, THIS loss strands a
            // refund (see the class javadoc). Still swallowed: failing the transaction now cannot
            // unsend anything, it would only roll back a confirm that already happened.
            log.warn("Failed to publish BookingConfirmationRejected bookingId={} paymentId={}; "
                            + "refund stranded until Phase-4 outbox lands: {}",
                    event.bookingId(), event.paymentId(), e.getMessage());
        }
    }
}
