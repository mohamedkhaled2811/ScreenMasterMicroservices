package com.gr74.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.notification.config.RabbitConfig;
import com.gr74.notification.service.NotificationService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * The thin AMQP shell's routing: which routing key is dispatched to which service method, with the
 * payload converted to the right record — and that an unexpected key is ignored, not thrown at.
 *
 * <p>Plus the trace restore: a {@code traceparent} header is re-joined as a
 * real child span and the {@code traceId} lands in the SLF4J MDC for the duration of the dispatch —
 * and is always cleared afterwards, so a pooled listener thread never mislabels a later message.
 *
 * <p>The listener never touches a broker here; it is constructed directly with mocked collaborators
 * (the {@code PaymentEventListenerTest} shape in miniature). What is NOT covered is the
 * queue/binding/converter wiring itself — that seam is exercised once, live, in the demo.
 */
class BookingEventListenerTest {

    private static final String TRACE_ID = "7f3ab9e2c1d44a02b8e1f0c3d5a67890";
    private static final String PARENT_SPAN_ID = "a1b2c3d4e5f60718";

    @Mock
    private NotificationService notificationService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Tracer tracer;

    private BookingEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new BookingEventListener(notificationService, objectMapper, tracer);
    }

    // The ticket fields are left null throughout this class on purpose: this is the AMQP ADAPTER's
    // test, and the adapter only routes and delegates — it never reads the payload's contents. The
    // service is mocked, so filling in a full ticket here would assert nothing and would have to be
    // updated every time the event contract grows. NotificationServiceTest covers the contents.

    @Test
    @DisplayName("booking-confirmed-key converts to BookingConfirmedEvent and delegates")
    void dispatchesConfirmedEvent() {
        BookingConfirmedEvent event = new BookingConfirmedEvent(1L, 101L, "BK-00001", "user-1",
                null, null, null, null, null, null, null, null, null, Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmedEvent.class))).thenReturn(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, null);

        verify(notificationService).processBookingConfirmed(event);
        verify(notificationService, never()).processBookingConfirmationRejected(any());
    }

    @Test
    @DisplayName("booking-confirmation-rejected-key converts to the rejection record and delegates")
    void dispatchesRejectedEvent() {
        BookingConfirmationRejectedEvent event = new BookingConfirmationRejectedEvent(2L, 202L,
                "BK-00002", 900L, ConfirmationRejectionReason.CANCELLED, "user-1",
                Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmationRejectedEvent.class)))
                .thenReturn(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY, null);

        verify(notificationService).processBookingConfirmationRejected(event);
        verify(notificationService, never()).processBookingConfirmed(any());
    }

    @Test
    @DisplayName("an unexpected routing key is ignored, not dispatched and not thrown")
    void ignoresUnexpectedRoutingKey() {
        listener.onBookingEvent(Map.of(), "some-unknown-key", null);

        verify(notificationService, never()).processBookingConfirmed(any());
        verify(notificationService, never()).processBookingConfirmationRejected(any());
    }

    @Test
    @DisplayName("a message with NO traceparent still processes — backwards compatibility")
    void noTraceparentStillProcesses() {
        BookingConfirmedEvent event = new BookingConfirmedEvent(3L, 303L, "BK-00003", "user-1",
                null, null, null, null, null, null, null, null, null, Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmedEvent.class))).thenReturn(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, null);

        verify(notificationService).processBookingConfirmed(event);
        // Nothing was restored, so nothing must be left in the MDC for the next message.
        assertThat(MDC.get("traceId")).isNull();
        verify(tracer, never()).spanBuilder();
    }

    @Test
    @DisplayName("a traceparent header restores the traceId into the MDC during processing, then clears it")
    void traceparentRestoresTraceIdAndClearsItAfter() {
        BookingConfirmedEvent event = new BookingConfirmedEvent(4L, 404L, "BK-00004", "user-1",
                null, null, null, null, null, null, null, null, null, Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmedEvent.class))).thenReturn(event);

        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE_ID);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        TraceContext.Builder builder = mock(TraceContext.Builder.class);
        when(builder.traceId(TRACE_ID)).thenReturn(builder);
        when(builder.spanId(PARENT_SPAN_ID)).thenReturn(builder);
        when(builder.sampled(anyBoolean())).thenReturn(builder);
        when(builder.build()).thenReturn(context);
        when(tracer.traceContextBuilder()).thenReturn(builder);

        Span.Builder spanBuilder = mock(Span.Builder.class);
        when(spanBuilder.setParent(context)).thenReturn(spanBuilder);
        when(spanBuilder.name("consume-booking-event")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);

        // Capture the MDC value AT the moment the service method runs — that is the observable
        // "the consumer's log lines carry the original trace id" contract.
        AtomicReference<String> mdcDuringProcessing = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringProcessing.set(MDC.get("traceId"));
            return null;
        }).when(notificationService).processBookingConfirmed(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY,
                "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");

        assertThat(mdcDuringProcessing.get()).isEqualTo(TRACE_ID);
        // The try/finally cleared it — no leak onto the pooled listener thread.
        assertThat(MDC.get("traceId")).isNull();
        verify(span).end();
    }

    @Test
    @DisplayName("a failed dispatch records the error on the span, still clears the MDC, and rethrows")
    void failedDispatchMarksSpanWithError() {
        BookingConfirmedEvent event = new BookingConfirmedEvent(6L, 606L, "BK-00006", "user-1",
                null, null, null, null, null, null, null, null, null, Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmedEvent.class))).thenReturn(event);

        TraceContext context = mock(TraceContext.class);
        when(context.traceId()).thenReturn(TRACE_ID);
        Span span = mock(Span.class);
        when(span.context()).thenReturn(context);
        when(tracer.withSpan(span)).thenReturn(mock(Tracer.SpanInScope.class));

        TraceContext.Builder builder = mock(TraceContext.Builder.class);
        when(builder.traceId(TRACE_ID)).thenReturn(builder);
        when(builder.spanId(PARENT_SPAN_ID)).thenReturn(builder);
        when(builder.sampled(anyBoolean())).thenReturn(builder);
        when(builder.build()).thenReturn(context);
        when(tracer.traceContextBuilder()).thenReturn(builder);

        Span.Builder spanBuilder = mock(Span.Builder.class);
        when(spanBuilder.setParent(context)).thenReturn(spanBuilder);
        when(spanBuilder.name("consume-booking-event")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);

        RuntimeException boom = new IllegalStateException("database down");
        doThrow(boom).when(notificationService).processBookingConfirmed(event);

        // The exception must escape unchanged: that is what nacks the message so RabbitMQ redelivers
        // it. Swallowing it to keep the span tidy would silently drop a customer's email.
        assertThatThrownBy(() -> listener.onBookingEvent(Map.of(),
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY,
                "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01"))
                .isSameAs(boom);

        verify(span).error(boom);
        verify(span).end();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("a malformed traceparent is ignored and the message still processes")
    void malformedTraceparentStillProcesses() {
        BookingConfirmedEvent event = new BookingConfirmedEvent(5L, 505L, "BK-00005", "user-1",
                null, null, null, null, null, null, null, null, null, Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(BookingConfirmedEvent.class))).thenReturn(event);

        listener.onBookingEvent(Map.of(), RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "not-a-header");

        verify(notificationService).processBookingConfirmed(event);
        assertThat(MDC.get("traceId")).isNull();
    }
}