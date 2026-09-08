package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.util.ReflectionTestUtils;

import com.gr74.booking.config.JpaAuditingConfig;
import com.gr74.booking.messaging.BookingConfirmationRejected;
import com.gr74.booking.messaging.BookingConfirmed;
import com.gr74.booking.messaging.ConfirmationRejectionReason;
import com.gr74.booking.messaging.PaymentFailedEvent;
import com.gr74.booking.messaging.PaymentSucceededEvent;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingSeat;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.service.BookingConfirmer.ConfirmOutcome;

/**
 * The saga step, tested by <b>direct invocation</b> of {@link BookingConfirmer} on H2 — no broker
 * (the {@code MovieProjectorTest} idiom). This deliberately does <em>not</em> exercise the
 * queue/binding/routing-key/converter wiring; that seam is covered once, manually, in the live
 * demo.
 *
 * <p>What it proves — the branches that are actually ours:
 * <ol>
 *   <li>a live PENDING hold confirms on exactly one row and raises {@code BookingConfirmed};</li>
 *   <li>a redelivered event re-reads CONFIRMED and publishes nothing twice (idempotent);</li>
 *   <li>an EXPIRED or CANCELLED booking yields a {@code BookingConfirmationRejected} carrying the
 *       event's {@code paymentId} — the compensation trigger 3.5 consumes;</li>
 *   <li>a PENDING booking whose hold already lapsed is expired inline and rejected deterministically
 *       (never left paid-but-undecided for the sweeper to maybe find);</li>
 *   <li>{@code PaymentFailed} mirrors {@code paymentStatus → FAILED} where still PENDING only —
 *       status and seats untouched, and a late failure after PAID is dropped.</li>
 * </ol>
 */
@DataJpaTest
@Import({BookingConfirmer.class, JpaAuditingConfig.class})
@RecordApplicationEvents
class BookingConfirmerTest {

    private static final Instant NOW = Instant.parse("2026-09-06T20:00:00Z");
    private static final String USER = "11111111-1111-1111-1111-111111111111";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private BookingConfirmer confirmer;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private ApplicationEvents events;

    @Test
    void confirmOnLivePendingHoldConfirmsAndPublishes() {
        Booking booking = persist("BK-CONFIRM01", BookingStatus.PENDING, NOW.plusSeconds(900));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 500L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.CONFIRMED);
        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reloaded.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        List<BookingConfirmed> published = events.stream(BookingConfirmed.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).bookingId()).isEqualTo(booking.getId());
        assertThat(published.get(0).bookingReference()).isEqualTo("BK-CONFIRM01");
        assertThat(published.get(0).userId()).isEqualTo(USER);
    }

    @Test
    void redeliveredEventAfterConfirmIsAnIdempotentNoOp() {
        Booking booking = persist("BK-REDELIVER1", BookingStatus.PENDING, NOW.plusSeconds(900));
        PaymentSucceededEvent event = succeeded(booking.getId(), 501L);

        assertThat(confirmer.confirmFromPayment(event)).isEqualTo(ConfirmOutcome.CONFIRMED);
        assertThat(confirmer.confirmFromPayment(event)).isEqualTo(ConfirmOutcome.ALREADY_CONFIRMED);

        assertThat(events.stream(BookingConfirmed.class).toList()).hasSize(1);
        assertThat(events.stream(BookingConfirmationRejected.class).toList()).isEmpty();
    }

    @Test
    void expiredBookingYieldsRejectionCarryingThePaymentId() {
        Booking booking = persist("BK-EXPIRED001", BookingStatus.EXPIRED, NOW.minusSeconds(60));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 502L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.REJECTED);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        List<BookingConfirmationRejected> rejected =
                events.stream(BookingConfirmationRejected.class).toList();
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).bookingId()).isEqualTo(booking.getId());
        assertThat(rejected.get(0).reason()).isEqualTo(ConfirmationRejectionReason.EXPIRED);
        assertThat(rejected.get(0).paymentId()).isEqualTo(502L);
        assertThat(events.stream(BookingConfirmed.class).toList()).isEmpty();
    }

    @Test
    void cancelledBookingYieldsRejectionWithCancelledReason() {
        Booking booking = persist("BK-CANCELLED1", BookingStatus.CANCELLED, NOW.plusSeconds(900));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 503L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.REJECTED);
        assertThat(events.stream(BookingConfirmationRejected.class).toList())
                .extracting(BookingConfirmationRejected::reason)
                .containsExactly(ConfirmationRejectionReason.CANCELLED);
    }

    @Test
    void pendingBookingWithLapsedHoldIsExpiredInlineAndRejected() {
        Booking booking = persist("BK-LAPSED0001", BookingStatus.PENDING, NOW.minusSeconds(60));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 504L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.REJECTED);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        List<BookingConfirmationRejected> rejected =
                events.stream(BookingConfirmationRejected.class).toList();
        assertThat(rejected).hasSize(1);
        assertThat(rejected.get(0).reason()).isEqualTo(ConfirmationRejectionReason.EXPIRED);
        assertThat(rejected.get(0).paymentId()).isEqualTo(504L);
    }

    @Test
    void succeededForUnknownBookingIsLoggedAndIgnored() {
        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(9_999_999L, 505L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.UNKNOWN_BOOKING);
        assertThat(events.stream(BookingConfirmed.class).toList()).isEmpty();
        assertThat(events.stream(BookingConfirmationRejected.class).toList()).isEmpty();
    }

    @Test
    void paymentFailedMirrorsOnlyLeavingStatusAndSeatsUntouched() {
        Booking booking = persistWithSeats("BK-MIRROR0001", BookingStatus.PENDING, NOW.plusSeconds(900));

        boolean mirrored = confirmer.mirrorFailure(
                new PaymentFailedEvent(11L, 506L, booking.getId(), 701L, "SANDBOX", "DECLINED", NOW));

        assertThat(mirrored).isTrue();
        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(reloaded.getSeats()).hasSize(2);
    }

    @Test
    void paymentFailedAfterSucceededIsDroppedAndKeepsPaid() {
        Booking booking = persist("BK-OOO-000001", BookingStatus.PENDING, NOW.plusSeconds(900));
        confirmer.confirmFromPayment(succeeded(booking.getId(), 507L));

        boolean mirrored = confirmer.mirrorFailure(
                new PaymentFailedEvent(12L, 507L, booking.getId(), 702L, "SANDBOX", "DECLINED", NOW));

        assertThat(mirrored).isFalse();
        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reloaded.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    private PaymentSucceededEvent succeeded(long bookingId, long paymentId) {
        return new PaymentSucceededEvent(10L, paymentId, bookingId, 700L,
                "SANDBOX", new BigDecimal("24.00"), "EGP", USER, NOW);
    }

    private Booking persist(String reference, BookingStatus status, Instant expiresAt) {
        Booking booking = new Booking(reference, USER, 1L, 603L, new BigDecimal("24.00"), "EGP", expiresAt);
        ReflectionTestUtils.setField(booking, "status", status);
        return bookings.saveAndFlush(booking);
    }

    private Booking persistWithSeats(String reference, BookingStatus status, Instant expiresAt) {
        Booking booking = new Booking(reference, USER, 1L, 603L, new BigDecimal("24.00"), "EGP", expiresAt);
        ReflectionTestUtils.setField(booking, "status", status);
        booking.addSeat(new BookingSeat(10L, new BigDecimal("12.00"), "STANDARD"));
        booking.addSeat(new BookingSeat(11L, new BigDecimal("12.00"), "STANDARD"));
        return bookings.saveAndFlush(booking);
    }
}
