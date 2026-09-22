package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gr74.booking.config.JpaAuditingConfig;
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.messaging.PaymentFailedEvent;
import com.gr74.booking.messaging.PaymentSucceededEvent;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingSeat;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;
import com.gr74.booking.outbox.OutboxEventType;
import com.gr74.booking.outbox.OutboxMessage;
import com.gr74.booking.outbox.OutboxMessageRepository;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.service.BookingConfirmer.ConfirmOutcome;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * The saga step, tested by <b>direct invocation</b> of {@link BookingConfirmer} on H2 — no broker
 * (the {@code MovieProjectorTest} idiom). This deliberately does <em>not</em> exercise the
 * queue/binding/routing-key/converter wiring; that seam is covered once, manually, in the live
 * demo.
 *
 * <p>What it proves — the branches that are actually ours:
 * <ol>
 *   <li>a live PENDING hold confirms on exactly one row and writes a {@code BOOKING_CONFIRMED}
 *       outbox row in the same transaction;</li>
 *   <li>a redelivered event re-reads CONFIRMED and writes nothing twice (idempotent);</li>
 *   <li>an EXPIRED or CANCELLED booking yields a {@code BOOKING_CONFIRMATION_REJECTED} outbox row
 *       carrying the event's {@code paymentId} — the compensation trigger 3.5 consumes;</li>
 *   <li>a PENDING booking whose hold already lapsed is expired inline and rejected deterministically
 *       (never left paid-but-undecided for the sweeper to maybe find);</li>
 *   <li>the transactional boundary (BUILD_PLAN 4.1): the outbox row commits <em>and rolls back</em>
 *       with the booking row — a dual write that can never drift apart;</li>
 *   <li>{@code PaymentFailed} mirrors {@code paymentStatus → FAILED} where still PENDING only —
 *       status and seats untouched, and a late failure after PAID is dropped.</li>
 * </ol>
 */
@DataJpaTest
@Import({BookingConfirmer.class, JpaAuditingConfig.class})
class BookingConfirmerTest {

    private static final Instant NOW = Instant.parse("2026-09-06T20:00:00Z");
    private static final String USER = "11111111-1111-1111-1111-111111111111";

    @TestConfiguration
    static class ConfirmerTestConfig {
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
    private BookingConfirmer confirmer;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private OutboxMessageRepository outbox;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** The confirmer captures the trace context onto the outbox row — mocked here so tests control it. */
    @MockitoBean
    private Tracer tracer;

    /**
     * The movie read model feeds the ticket snapshot (title + poster) onto BookingConfirmed. Mocked
     * because it fronts a Catalog HTTP client, and because what this class tests is the CONFIRM's
     * transactional behaviour — not how a title is resolved. It returns empty by default, which also
     * exercises the degradation that matters: an unresolvable movie must still confirm the booking
     * and still announce it. Money has already moved; a missing poster cannot veto that.
     */
    @MockitoBean
    private MovieReadModel movies;

    @Test
    void confirmOnLivePendingHoldConfirmsAndWritesOutboxRow() {
        Booking booking = persist("BK-CONFIRM01", BookingStatus.PENDING, NOW.plusSeconds(900));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 500L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.CONFIRMED);
        Booking reloaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reloaded.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        List<OutboxMessage> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        OutboxMessage row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMED);
        assertThat(row.getRoutingKey()).isEqualTo(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY);
        assertThat(row.getAggregateId()).isEqualTo(booking.getId());
        assertThat(row.isPending()).isTrue();
        Map<String, Object> payload = payloadOf(row);
        // The stored eventId is the 0L placeholder; the relay stamps the outbox row id on the wire.
        assertThat(payload.get("eventId")).isEqualTo(0);
        assertThat(payload.get("bookingReference")).isEqualTo("BK-CONFIRM01");
        assertThat(payload.get("userId")).isEqualTo(USER);
    }

    @Test
    void redeliveredEventAfterConfirmIsAnIdempotentNoOp() {
        Booking booking = persist("BK-REDELIVER1", BookingStatus.PENDING, NOW.plusSeconds(900));
        PaymentSucceededEvent event = succeeded(booking.getId(), 501L);

        assertThat(confirmer.confirmFromPayment(event)).isEqualTo(ConfirmOutcome.CONFIRMED);
        assertThat(confirmer.confirmFromPayment(event)).isEqualTo(ConfirmOutcome.ALREADY_CONFIRMED);

        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1L);
        assertThat(outbox.findAll().get(0).getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMED);
    }

    @Test
    void expiredBookingYieldsRejectionCarryingThePaymentId() {
        Booking booking = persist("BK-EXPIRED001", BookingStatus.EXPIRED, NOW.minusSeconds(60));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 502L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.REJECTED);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        List<OutboxMessage> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        OutboxMessage row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMATION_REJECTED);
        assertThat(row.getRoutingKey()).isEqualTo(RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY);
        Map<String, Object> payload = payloadOf(row);
        assertThat(payload.get("paymentId")).isEqualTo(502);
        assertThat(payload.get("reason")).isEqualTo("EXPIRED");
    }

    @Test
    void cancelledBookingYieldsRejectionWithCancelledReason() {
        Booking booking = persist("BK-CANCELLED1", BookingStatus.CANCELLED, NOW.plusSeconds(900));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 503L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.REJECTED);
        assertThat(outbox.findAll())
                .extracting(OutboxMessage::getEventType)
                .containsExactly(OutboxEventType.BOOKING_CONFIRMATION_REJECTED);
        assertThat(payloadOf(outbox.findAll().get(0)).get("reason")).isEqualTo("CANCELLED");
    }

    @Test
    void pendingBookingWithLapsedHoldIsExpiredInlineAndRejected() {
        Booking booking = persist("BK-LAPSED0001", BookingStatus.PENDING, NOW.minusSeconds(60));

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 504L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.REJECTED);
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        List<OutboxMessage> rows = outbox.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEventType()).isEqualTo(OutboxEventType.BOOKING_CONFIRMATION_REJECTED);
        assertThat(payloadOf(rows.get(0)).get("paymentId")).isEqualTo(504);
    }

    @Test
    void succeededForUnknownBookingIsLoggedAndIgnored() {
        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(9_999_999L, 505L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.UNKNOWN_BOOKING);
        assertThat(outbox.count()).isZero();
    }

    @Test
    void outboxRowCapturesTheActiveTraceContext() {
        Booking booking = persist("BK-TRACE0001", BookingStatus.PENDING, NOW.plusSeconds(900));
        String traceId = "7f3ab9e2c1d44a02b8e1f0c3d5a67890";
        String spanId = "a1b2c3d4e5f60718";
        TraceContext context = mock(TraceContext.class);
        given(context.traceId()).willReturn(traceId);
        given(context.spanId()).willReturn(spanId);
        Span span = mock(Span.class);
        given(span.context()).willReturn(context);
        given(tracer.currentSpan()).willReturn(span);

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 520L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.CONFIRMED);
        OutboxMessage row = outbox.findAll().get(0);
        assertThat(row.getTraceId()).isEqualTo(traceId);
        assertThat(row.getSpanId()).isEqualTo(spanId);
    }

    @Test
    void outboxRowStoresNullTraceWhenNoSpanIsActive() {
        Booking booking = persist("BK-NOTRACE01", BookingStatus.PENDING, NOW.plusSeconds(900));
        // The default mock returns null from currentSpan() — the null-safe capture path (a test,
        // a scheduled path, a consumer with no restored trace).

        ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 521L));

        assertThat(outcome).isEqualTo(ConfirmOutcome.CONFIRMED);
        OutboxMessage row = outbox.findAll().get(0);
        assertThat(row.getTraceId()).isNull();
        assertThat(row.getSpanId()).isNull();
    }

    /**
     * The dual-write guarantee, exercised from a <em>real</em> transaction boundary.
     *
     * <p>{@code @Transactional(NOT_SUPPORTED)} suspends the transaction {@code @DataJpaTest} wraps
     * the test in, so {@code TransactionTemplate} below sees production conditions rather than the
     * harness's ambient transaction. Inside the template both writes are visible together; a
     * {@code setRollbackOnly} takes <b>both</b> back — the booking cannot stay confirmed with its
     * announcement lost, nor can an announcement outlive a booking that never confirmed. Before the
     * outbox, this path published AFTER_COMMIT, so a rollback here would have dropped the event the
     * moment the transaction failed — the exact exposure Phase 4 closes.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void outboxRowCommitsAndRollsBackWithTheBookingRow() {
        Booking booking = persist("BK-BOUNDARY01", BookingStatus.PENDING, NOW.plusSeconds(900));
        try {
            transactionTemplate.execute(status -> {
                ConfirmOutcome outcome = confirmer.confirmFromPayment(succeeded(booking.getId(), 510L));

                assertThat(outcome).isEqualTo(ConfirmOutcome.CONFIRMED);
                // Inside the transaction the booking and its announcement are visible together ...
                assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.CONFIRMED);
                assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(1L);

                status.setRollbackOnly();
                return null;
            });

            // ... and a rollback takes BOTH back — one atomic unit, never a dangling half.
            assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus())
                    .isEqualTo(BookingStatus.PENDING);
            assertThat(outbox.count()).isZero();
        } finally {
            // This test's writes really commit/roll back (NOT_SUPPORTED), so clean up the booking
            // persisted outside the template rather than leaving it for the harness rollback.
            bookings.deleteById(booking.getId());
        }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadOf(OutboxMessage row) {
        try {
            return objectMapper.readValue(row.getPayload(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Stored outbox payload is not JSON: " + row.getPayload(), e);
        }
    }
}