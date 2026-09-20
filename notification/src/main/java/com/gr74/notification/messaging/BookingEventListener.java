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
 * The AMQP adapter for Booking's booking-outcome stream — a deliberately thin shell over
 * {@link NotificationService}. All the interesting behaviour (the claim-first dedupe) lives in the
 * service so it is unit-testable without a broker; this class only bridges the queue to it.
 *
 * <p>An exception thrown here (e.g. the DB is momentarily down) propagates, the message is
 * <em>not</em> acked, and RabbitMQ redelivers it — safe precisely because the service is idempotent
 * (a redelivery re-inserts the same eventId and the dedupe no-ops). No transaction, no try/catch:
 * the {@code PaymentEventListener} idiom.
 *
 * <p><b>Why a {@code Map} payload plus routing-key dispatch instead of two typed params.</b> One
 * queue carries two event types, and Booking's outbox relay publishes them as
 * {@code Map<String, Object>} (it parses the stored JSON and injects the outbox row id as
 * {@code eventId}), so the {@code __TypeId__} header names {@code java.util.LinkedHashMap} — and the
 * JSON converter resolves TYPE_ID ahead of the listener method's parameter type. Typed record
 * parameters could therefore never bind; the honest shape is to receive the Map, dispatch on the
 * received routing key (the binding key that delivered it), and convert to the records Notification
 * owns.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    // The endpoint-level flag (not the factory's) is what the registry honors: false in the
    // hermetic suite — there is no broker to connect to, and the tests drive this method
    // directly — true everywhere else.
    @RabbitListener(queues = RabbitConfig.NOTIFICATION_BOOKING_EVENTS_QUEUE,
            autoStartup = "${notification.amqp.listener.auto-startup:true}")
    public void onBookingEvent(Map<String, Object> payload,
            @Header(AmqpHeaders.RECEIVED_ROUTING_KEY) String routingKey,
            @Header(value = "traceparent", required = false) String traceparent) {
        runInTrace(traceparent, () -> dispatch(payload, routingKey));
    }

    /**
     * The original business dispatch — kept a private method so {@link #runInTrace} can wrap it
     * without tangling the trace-restore lifecycle into the routing switch.
     */
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

    /**
     * Re-join the trace Booking's outbox relay persisted — the consumer half of Phase-6 decision B1.
     *
     * <p>This listener runs on a RabbitMQ consumer thread where <em>no</em> live Micrometer context
     * exists: the outbox hop meant the trace was never propagated automatically (the relay publishes
     * on a scheduler thread, long after the request context died), so the relay stamped it onto the
     * message instead. Here we read it back, build a real child span of the persisted parent, and
     * make it current for the duration of the dispatch — so the "email sent" log line carries the
     * same trace id as the booking that caused it.
     *
     * <p>The MDC {@code traceId} is set explicitly because the log pattern renders {@code %X{traceId}}
     * — if the bridge does not auto-populate it for a hand-started span, this guarantees the line is
     * still correlated. The try/finally is not optional: a leaked MDC entry on a pooled listener
     * thread would mislabel every later message that thread handles.
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
            // Record the failure ON the span before it ends, then rethrow unchanged so the message
            // is still nacked and redelivered. Without this the span closes looking successful, and
            // Zipkin would show a green waterfall for the exact failure this phase exists to debug.
            span.error(e);
            throw e;
        } finally {
            MDC.remove("traceId");
            span.end();
        }
    }

    /**
     * Parse a W3C {@code traceparent} ({@code 00-{traceId}-{spanId}-{flags}}) and create a started
     * child span of the persisted parent. Null when the header is absent or malformed — the
     * backwards-compatible path that simply processes without a restored trace.
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
        return tracer.spanBuilder().setParent(parent).name("consume-booking-event").start();
    }
}