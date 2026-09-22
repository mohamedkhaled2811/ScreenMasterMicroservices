package com.gr74.booking.messaging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.BookingConfirmer;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AMQP adapter forwarding Payment outcome events to the confirmer, dispatched by routing key.
 * Receives a Map payload because the converter resolves the type header ahead of the method signature.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final BookingConfirmer confirmer;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    /**
     * Trace header persisted by the outbox relay; absent when published without trace context.
     */
    @RabbitListener(queues = RabbitConfig.PAYMENT_EVENTS_QUEUE)
    public void onPaymentEvent(Map<String, Object> payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
            @Header(value = "traceparent", required = false) String traceparent) {
        runInTrace(traceparent, () -> dispatch(payload, routingKey));
    }

    /** Business dispatch, wrapped by {@link #runInTrace} to keep trace handling separate. */
    private void dispatch(Map<String, Object> payload, String routingKey) {
        switch (routingKey) {
            case RabbitConfig.PAYMENT_SUCCEEDED_ROUTING_KEY -> {
                PaymentSucceededEvent event =
                        objectMapper.convertValue(payload, PaymentSucceededEvent.class);
                log.debug("Received PaymentSucceeded paymentId={} bookingId={} eventId={}",
                        event.paymentId(), event.bookingId(), event.eventId());
                confirmer.confirmFromPayment(event);
            }
            case RabbitConfig.PAYMENT_FAILED_ROUTING_KEY -> {
                PaymentFailedEvent event =
                        objectMapper.convertValue(payload, PaymentFailedEvent.class);
                log.debug("Received PaymentFailed paymentId={} bookingId={} eventId={}",
                        event.paymentId(), event.bookingId(), event.eventId());
                confirmer.mirrorFailure(event);
            }
            default -> log.warn("Ignoring payment event with unexpected routing key '{}' — "
                    + "no binding should deliver this; check RabbitConfig", routingKey);
        }
    }

    /**
     * Re-joins the persisted trace and runs the dispatch inside it.
     */
    private void runInTrace(String traceparent, Runnable work) {
        Span span = spanFrom(traceparent);
        if (span == null) {
            work.run();
            return;
        }
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            MDC.put("traceId", span.context().traceId());
            work.run();
        } catch (RuntimeException e) {
            // Record on the span, then rethrow so the message is nacked and redelivered.
            span.error(e);
            throw e;
        } finally {
            MDC.remove("traceId");
            span.end();
        }
    }

    /**
     * Parses a W3C {@code traceparent} into a started child span; null when absent or malformed.
     */
    private Span spanFrom(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length < 4 || !"00".equals(parts[0])) {
            log.debug("Ignoring malformed traceparent '{}' — processing without a restored trace", traceparent);
            return null;
        }
        TraceContext parent = tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled("01".equals(parts[3]))
                .build();
        return tracer.spanBuilder().setParent(parent).name("consume-payment-event").start();
    }
}
