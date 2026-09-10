package com.gr74.booking.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.messaging.BookingConfirmationRejected;
import com.gr74.booking.messaging.BookingConfirmed;
import com.gr74.booking.messaging.ConfirmationRejectionReason;
import com.gr74.booking.messaging.PaymentFailedEvent;
import com.gr74.booking.messaging.PaymentSucceededEvent;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;
import com.gr74.booking.repository.BookingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies Payment's outcomes to Booking's holds — the saga step (BUILD_PLAN 3.3), behind the thin
 * {@link com.gr74.booking.messaging.PaymentEventListener} shell.
 *
 * <p>Kept a plain {@code @Transactional} service (not the {@code @RabbitListener} itself) so it
 * can be unit-tested by direct invocation, and so the AMQP adapter stays a thin shell — the
 * {@code MovieProjector} idiom. A throw here means no ack and a redelivery, safe precisely
 * because every path below is idempotent: the confirm is one conditional UPDATE (re-running it
 * updates zero rows and re-reads CONFIRMED), and the failure mirror only lands on PENDING.
 *
 * <p><b>Nothing is ever decided from {@code paymentStatus}.</b> It is a read-model mirror for
 * display ("last attempt failed, you may retry"); the seat guard looks at {@code status} only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingConfirmer {

    private final BookingRepository bookings;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** What one {@code PaymentSucceeded} decided — returned so tests can assert the branch. */
    public enum ConfirmOutcome {
        /** The conditional update matched: PENDING with a live hold → CONFIRMED. */
        CONFIRMED,
        /** Zero rows, re-read CONFIRMED: a redelivered event. Idempotent no-op. */
        ALREADY_CONFIRMED,
        /** Zero rows, re-read terminal-or-lapsed: a rejection was published for auto-refund. */
        REJECTED,
        /** Zero rows, no such booking: logged, nothing published (should not happen — Payment
         * creates obligations off a booking read, and bookings are never deleted). */
        UNKNOWN_BOOKING
    }

    /**
     * Confirm a booking off a {@code PaymentSucceeded} event.
     *
     * <p>One conditional UPDATE decides the race with the expiry sweeper at the database: id
     * match + still PENDING + hold still live. One row → CONFIRMED + a {@link BookingConfirmed}
     * application event (published to the broker AFTER_COMMIT by
     * {@link com.gr74.booking.messaging.BookingEventPublisher}). Zero rows → re-read and branch:
     * CONFIRMED is a redelivery (no-op); EXPIRED/CANCELLED publishes a
     * {@link BookingConfirmationRejected} carrying the event's {@code paymentId} so Payment can
     * refund without a lookup — the compensation trigger step 3.5 consumes.
     */
    @Transactional
    public ConfirmOutcome confirmFromPayment(PaymentSucceededEvent event) {
        Instant now = clock.instant();
        int rows = bookings.confirmIfStillPending(
                event.bookingId(), now, BookingStatus.PENDING, BookingStatus.CONFIRMED, PaymentStatus.PAID);
        if (rows == 1) {
            // clearAutomatically flushed the persistence context, so this re-read is fresh.
            Booking booking = bookings.findById(event.bookingId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Booking " + event.bookingId() + " confirmed then vanished mid-transaction"));
            events.publishEvent(new BookingConfirmed(UUID.randomUUID().toString(),
                    booking.getId(), booking.getBookingReference(), booking.getUserId(), now));
            log.info("Confirmed booking id={} ref={} off paymentId={} (eventId={})",
                    booking.getId(), booking.getBookingReference(), event.paymentId(), event.eventId());
            return ConfirmOutcome.CONFIRMED;
        }
        return bookings.findById(event.bookingId())
                .map(booking -> reject(booking, event, now))
                .orElseGet(() -> {
                    log.warn("PaymentSucceeded for unknown bookingId={} paymentId={} eventId={} — ignored",
                            event.bookingId(), event.paymentId(), event.eventId());
                    return ConfirmOutcome.UNKNOWN_BOOKING;
                });
    }

    /**
     * Mirror a {@code PaymentFailed} onto the read-model field — display only.
     *
     * <p>Conditional on still-PENDING so a late failure can never overwrite a PAID, and seats stay
     * held: the user retries until the hold lapses. Returns whether the mirror landed (tests assert
     * the out-of-order drop through this).
     */
    @Transactional
    public boolean mirrorFailure(PaymentFailedEvent event) {
        int rows = bookings.mirrorPaymentFailure(
                event.bookingId(), BookingStatus.PENDING, PaymentStatus.FAILED);
        if (rows == 1) {
            log.info("Mirrored PaymentFailed onto bookingId={} (attemptId={} reason={}) — seats stay held",
                    event.bookingId(), event.attemptId(), event.failureReason());
            return true;
        }
        log.debug("Dropped PaymentFailed for bookingId={} — no longer PENDING (eventId={})",
                event.bookingId(), event.eventId());
        return false;
    }

    private ConfirmOutcome reject(Booking booking, PaymentSucceededEvent event, Instant now) {
        return switch (booking.getStatus()) {
            case CONFIRMED -> {
                log.debug("Redelivered PaymentSucceeded for already-CONFIRMED booking id={} — no-op (eventId={})",
                        booking.getId(), event.eventId());
                yield ConfirmOutcome.ALREADY_CONFIRMED;
            }
            case EXPIRED, CANCELLED -> {
                publishRejection(booking, event,
                        ConfirmationRejectionReason.valueOf(booking.getStatus().name()), now);
                yield ConfirmOutcome.REJECTED;
            }
            case PENDING -> rejectLapsedHold(booking, event, now);
        };
    }

    /**
     * The update matched zero rows yet the booking still reads PENDING — so the hold must have
     * lapsed between... more precisely, the {@code expiresAt} guard failed at update time while
     * the status was PENDING (a concurrent confirm could not have won: it needs a live hold too,
     * and a concurrent sweeper flip would read back EXPIRED, not PENDING).
     *
     * <p>Rather than trusting the sweeper to arrive within the minute, expire the hold inline with
     * the same guarded update and publish the rejection now: a paid-but-undecided event that merely
     * went back on the queue would strand the customer's refund if the sweeper were down. Either
     * order ends the same — EXPIRED plus an auto-refund — because the sweeper's update is guarded
     * identically and emits nothing itself.
     */
    private ConfirmOutcome rejectLapsedHold(Booking booking, PaymentSucceededEvent event, Instant now) {
        bookings.expireIfStillPending(booking.getId(), BookingStatus.PENDING, BookingStatus.EXPIRED);
        publishRejection(booking, event, ConfirmationRejectionReason.EXPIRED, now);
        return ConfirmOutcome.REJECTED;
    }

    private void publishRejection(Booking booking, PaymentSucceededEvent event,
            ConfirmationRejectionReason reason, Instant now) {
        events.publishEvent(new BookingConfirmationRejected(UUID.randomUUID().toString(),
                booking.getId(), booking.getBookingReference(), event.paymentId(),
                reason, booking.getUserId(), now));
        log.info("PaymentSucceeded arrived too late for booking id={} ref={} (status={}) — "
                        + "rejection published for auto-refund of paymentId={} (eventId={})",
                booking.getId(), booking.getBookingReference(), booking.getStatus(),
                event.paymentId(), event.eventId());
    }
}
