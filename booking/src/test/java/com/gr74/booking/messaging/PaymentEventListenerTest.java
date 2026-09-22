package com.gr74.booking.messaging;

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

import java.math.BigDecimal;
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
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.BookingConfirmer;
import com.gr74.booking.service.BookingConfirmer.ConfirmOutcome;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

/**
 * Routing-key dispatch and trace restore for the payment event listener.
 */
class PaymentEventListenerTest {

    private static final String TRACE_ID = "7f3ab9e2c1d44a02b8e1f0c3d5a67890";
    private static final String PARENT_SPAN_ID = "a1b2c3d4e5f60718";

    @Mock private BookingConfirmer confirmer;
    @Mock private ObjectMapper objectMapper;
    @Mock private Tracer tracer;

    private PaymentEventListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new PaymentEventListener(confirmer, objectMapper, tracer);
    }

    @Test
    @DisplayName("payment-succeeded-key converts to PaymentSucceededEvent and confirms")
    void dispatchesSucceededEvent() {
        PaymentSucceededEvent event = new PaymentSucceededEvent(1L, 101L, 501L, 701L, "SANDBOX",
                new BigDecimal("24.00"), "EGP", "user-1", Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(PaymentSucceededEvent.class))).thenReturn(event);

        listener.onPaymentEvent(Map.of(), RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY, null);

        verify(confirmer).confirmFromPayment(event);
        verify(confirmer, never()).mirrorFailure(any());
    }

    @Test
    @DisplayName("payment-failed-key converts to PaymentFailedEvent and mirrors the failure")
    void dispatchesFailedEvent() {
        PaymentFailedEvent event = new PaymentFailedEvent(2L, 202L, 602L, 702L, "SANDBOX", "DECLINED",
                Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(PaymentFailedEvent.class))).thenReturn(event);

        listener.onPaymentEvent(Map.of(), RabbitConfig.PAYMENT_FAILED_ROUTING_KEY, null);

        verify(confirmer).mirrorFailure(event);
        verify(confirmer, never()).confirmFromPayment(any());
    }

    @Test
    @DisplayName("an unexpected routing key is ignored, not dispatched and not thrown")
    void ignoresUnexpectedRoutingKey() {
        listener.onPaymentEvent(Map.of(), "some-unknown-key", null);

        verify(confirmer, never()).confirmFromPayment(any());
        verify(confirmer, never()).mirrorFailure(any());
    }

    @Test
    @DisplayName("a message with NO traceparent still processes — backwards compatibility")
    void noTraceparentStillProcesses() {
        PaymentSucceededEvent event = new PaymentSucceededEvent(3L, 303L, 503L, 703L, "SANDBOX",
                new BigDecimal("24.00"), "EGP", "user-1", Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(PaymentSucceededEvent.class))).thenReturn(event);

        listener.onPaymentEvent(Map.of(), RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY, null);

        verify(confirmer).confirmFromPayment(event);
        assertThat(MDC.get("traceId")).isNull();
        verify(tracer, never()).spanBuilder();
    }

    @Test
    @DisplayName("a traceparent header restores the traceId into the MDC during processing, then clears it")
    void traceparentRestoresTraceIdAndClearsItAfter() {
        PaymentSucceededEvent event = new PaymentSucceededEvent(4L, 404L, 504L, 704L, "SANDBOX",
                new BigDecimal("24.00"), "EGP", "user-1", Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(PaymentSucceededEvent.class))).thenReturn(event);

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
        when(spanBuilder.name("consume-payment-event")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);

        AtomicReference<String> mdcDuringProcessing = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringProcessing.set(MDC.get("traceId"));
            return ConfirmOutcome.CONFIRMED;
        }).when(confirmer).confirmFromPayment(event);

        listener.onPaymentEvent(Map.of(), RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY,
                "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");

        assertThat(mdcDuringProcessing.get()).isEqualTo(TRACE_ID);
        assertThat(MDC.get("traceId")).isNull();
        verify(span).end();
    }

    @Test
    @DisplayName("a failed confirm records the error on the span, still clears the MDC, and rethrows")
    void failedDispatchMarksSpanWithError() {
        PaymentSucceededEvent event = new PaymentSucceededEvent(6L, 606L, 506L, 706L, "SANDBOX",
                new BigDecimal("24.00"), "EGP", "user-1", Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(PaymentSucceededEvent.class))).thenReturn(event);

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
        when(spanBuilder.name("consume-payment-event")).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(span);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);

        RuntimeException boom = new IllegalStateException("database down");
        doThrow(boom).when(confirmer).confirmFromPayment(event);

        // Must escape unchanged so RabbitMQ redelivers the message.
        assertThatThrownBy(() -> listener.onPaymentEvent(Map.of(),
                RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY,
                "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01"))
                .isSameAs(boom);

        verify(span).error(boom);
        verify(span).end();
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("a malformed traceparent is ignored and the message still processes")
    void malformedTraceparentStillProcesses() {
        PaymentSucceededEvent event = new PaymentSucceededEvent(5L, 505L, 505L, 705L, "SANDBOX",
                new BigDecimal("24.00"), "EGP", "user-1", Instant.parse("2026-09-13T12:00:00Z"));
        when(objectMapper.convertValue(any(Map.class), eq(PaymentSucceededEvent.class))).thenReturn(event);

        listener.onPaymentEvent(Map.of(), RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY, "not-a-header");

        verify(confirmer).confirmFromPayment(event);
        assertThat(MDC.get("traceId")).isNull();
    }
}