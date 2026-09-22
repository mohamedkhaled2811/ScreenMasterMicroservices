package com.gr74.notification.messaging;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.notification.config.RabbitConfig;
import com.gr74.notification.service.NotificationService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges the booking-outcome queue to {@link NotificationService}.
 * Receives a Map payload and dispatches on the routing key, since the publisher sends maps.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    // Disabled via property when no broker is available.
    @RabbitListener(queues = RabbitConfig.NOTIFICATION_BOOKING_EVENTS_QUEUE,
            autoStartup = "${notification.amqp.listener.auto-startup:true}")
    public void onBookingEvent(Map<String, Object> payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
            @Header(value = "traceparent", required = false) String traceparent) {
        runInTrace(traceparent, () -> dispatch(payload, routingKey));
    }

    /** Routes the payload to the matching handler by routing key. */
    private void dispatch(Map<String, Object> payload, String routingKey) {
        switch (routingKey) {
            case RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY -> {
                BookingConfirmedEvent event =
                        objectMapper.convertValue(payload, BookingConfirmedEvent.class);
                log.debug("Received BookingConfirmed eventId={} bookingId={}",
                        event.eventId(), event.bookingId());
                notificationService.processBookingConfirmed(event);
            }
            case RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY -> {
                BookingConfirmationRejectedEvent event =
                        objectMapper.convertValue(payload, BookingConfirmationRejectedEvent.class);
                log.debug("Received BookingConfirmationRejected eventId={} bookingId={}",
                        event.eventId(), event.bookingId());
                notificationService.processBookingConfirmationRejected(event);
            }
            default -> log.warn("Ignoring booking event with unexpected routing key '{}' — "
                    + "no binding should deliver this; check RabbitConfig", routingKey);
        }
    }

    /** Runs the dispatch inside the trace from the {@code traceparent} header. */
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
            // Mark the span failed, then rethrow so the message redelivers.
            span.error(e);
            throw e;
        } finally {
            MDC.remove("traceId");
            span.end();
        }
    }

    /** Builds a child span from the {@code traceparent} header, or null when absent or malformed. */
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
        return tracer.spanBuilder().setParent(parent).name("consume-booking-event").start();
    }
}