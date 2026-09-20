package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gr74.booking.config.JpaAuditingConfig;
import com.gr74.booking.messaging.PaymentSucceededEvent;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingSeat;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.outbox.OutboxEventType;
import com.gr74.booking.outbox.OutboxMessage;
import com.gr74.booking.outbox.OutboxMessageRepository;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.service.BookingConfirmer.ConfirmOutcome;

import io.micrometer.tracing.Tracer;
import jakarta.persistence.EntityManager;

/**
 * The hold-expiry sweeper against a frozen clock.
 *
 * <p>What it proves:
 * <ol>
 *   <li>PENDING past {@code expiresAt} flips to EXPIRED, and the seats become available again to
 *       {@code findSeatIdsHeldForShowtime} — while the {@code booking_seats} rows still exist
 *       (the audit-trail assertion: release is a status flip, never a delete);</li>
 *   <li>live holds and non-PENDING rows are untouched;</li>
 *   <li>the race, explicitly: a booking confirming at the same instant the sweeper runs ends
 *       CONFIRMED, not EXPIRED — the conditional update decides, and the loser observes zero
 *       rows;</li>
 *   <li>a tick whose repository throws still does not kill the scheduler (caught internally).</li>
 * </ol>
 */
@DataJpaTest
@Import({BookingExpirySweeper.class, BookingExpirer.class, BookingConfirmer.class,
        JpaAuditingConfig.class})
class BookingExpirySweeperTest {

    private static final Instant NOW = Instant.parse("2026-09-06T20:16:00Z");
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final Set<BookingStatus> ACTIVE = Set.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        /** @DataJpaTest does not load Jackson; the confirmer serializes its outbox payloads with it.
         * The JavaTimeModule is required for the {@code Instant} fields, matching Boot's real mapper. */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @Autowired
    private BookingExpirySweeper sweeper;

    @Autowired
    private BookingExpirer expirer;

    @Autowired
    private BookingConfirmer confirmer;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager em;

    /** BookingConfirmer captures the trace context onto outbox rows; mocked here so the sweep
     * tests do not need a tracing setup (the slice has none). */
    @MockitoBean
    private Tracer tracer;

    @Test
    void lapsedHoldExpiresSeatsFreedButRowsKept() {
        Booking booking = persist("BK-SWEEP0001", BookingStatus.PENDING, NOW.minusSeconds(60), 10L);

        int expired = expirer.expireDueBookings(NOW);

        assertThat(expired).isEqualTo(1);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        // Freed by the status flip: no longer reported as held ...
        assertThat(bookings.findSeatIdsHeldForShowtime(1L, List.of(10L), ACTIVE)).isEmpty();
        // ... but the line-item rows still exist as history.
        Long seatRows = em.createQuery(
                "select count(bs) from BookingSeat bs where bs.booking.id = :id", Long.class)
                .setParameter("id", booking.getId())
                .getSingleResult();
        assertThat(seatRows).isEqualTo(1L);
    }

    @Test
    void liveHoldsAndNonPendingRowsAreUntouched() {
        Booking live = persist("BK-SWEEP0002", BookingStatus.PENDING, NOW.plusSeconds(60), 11L);
        Booking confirmed = persist("BK-SWEEP0003", BookingStatus.CONFIRMED, NOW.minusSeconds(3600), 12L);

        assertThat(expirer.expireDueBookings(NOW)).isZero();

        assertThat(bookings.findById(live.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING);
        assertThat(bookings.findById(confirmed.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(bookings.findSeatIdsHeldForShowtime(1L, List.of(11L, 12L), ACTIVE))
                .containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    void confirmRacingTheSweeperWinsWhileTheHoldIsLive() {
        // The payment lands inside the window; the sweeper ticks at the same instant.
        Booking booking = persist("BK-RACE-00001", BookingStatus.PENDING, NOW.plusSeconds(1), 13L);

        assertThat(confirmer.confirmFromPayment(succeeded(booking.getId(), 600L)))
                .isEqualTo(ConfirmOutcome.CONFIRMED);
        assertThat(expirer.expireDueBookings(NOW)).isZero();

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void sweeperFirstThenLatePaymentYieldsRejectionForAutoRefund() {
        Booking booking = persist("BK-RACE-00002", BookingStatus.PENDING, NOW.minusSeconds(1), 14L);

        assertThat(expirer.expireDueBookings(NOW)).isEqualTo(1);
        assertThat(confirmer.confirmFromPayment(succeeded(booking.getId(), 601L)))
                .isEqualTo(ConfirmOutcome.REJECTED);

        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        List<OutboxMessage> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMATION_REJECTED);
        assertThat(payloadOf(rows.get(0)).get("paymentId")).isEqualTo(601);
    }

    /**
     * The regression guard for the self-invocation bug.
     *
     * <p>{@code @Transactional(NOT_SUPPORTED)} is the whole point: {@code @DataJpaTest} normally
     * wraps each test in a transaction, which would hand {@code tick()} the very transaction
     * production lacks — so a test without this annotation passes against the broken code and
     * proves nothing. Suspending it reproduces the scheduler's real conditions, where the
     * {@code @Modifying} update must find a transaction opened by {@link BookingExpirer}'s own
     * proxy or throw.
     *
     * <p>Consequence: this write really commits, so the row is cleaned up explicitly rather than
     * rolled back with the test.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void tickOnTheProxiedBeanActuallyExpiresLapsedHolds() {
        Booking booking = persist("BK-SWEEP0004", BookingStatus.PENDING, NOW.minusSeconds(60), 15L);
        try {
            sweeper.tick();

            assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                    .isEqualTo(BookingStatus.EXPIRED);
        } finally {
            bookings.deleteById(booking.getId());
        }
    }

    @Test
    void tickCatchesRepositoryFailureInsteadOfKillingTheScheduler() {
        BookingExpirer failing = mock(BookingExpirer.class);
        given(failing.expireDueBookings(any())).willThrow(new RuntimeException("db down"));
        BookingExpirySweeper fragile = new BookingExpirySweeper(failing, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatNoException().isThrownBy(fragile::tick);
    }

    private PaymentSucceededEvent succeeded(long bookingId, long paymentId) {
        return new PaymentSucceededEvent(20L, paymentId, bookingId, 800L,
                "SANDBOX", new BigDecimal("12.00"), "EGP", USER, NOW);
    }

    private Booking persist(String reference, BookingStatus status, Instant expiresAt, long seatId) {
        Booking booking = new Booking(reference, USER, 1L, 603L, new BigDecimal("12.00"), "EGP", expiresAt);
        ReflectionTestUtils.setField(booking, "status", status);
        booking.addSeat(new BookingSeat(seatId, new BigDecimal("12.00"), "STANDARD"));
        return bookings.saveAndFlush(booking);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(OutboxMessage row) {
        try {
            return objectMapper.readValue(row.getPayload(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored outbox payload is not JSON: " + row.getPayload(), e);
        }
    }
}
