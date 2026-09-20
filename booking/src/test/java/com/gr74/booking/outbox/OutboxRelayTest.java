package com.gr74.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.booking.config.OutboxProps;
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.OutboxWriter;

/**
 * The relay's guarantees, against a mocked claim (H2 cannot run the native
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, so the query itself is never exercised here — the
 * boundary test in {@code BookingConfirmerTest} proves the row commits and rolls back with the
 * business write instead).
 *
 * <p>A plain unit test on purpose: the logic under test is ordering, error handling, the
 * stable-eventId injection, and the traceparent stamping, not wiring. Mirrors {@code payment}'s
 * {@code OutboxRelayTest}.
 */
class OutboxRelayTest {

    private final OutboxWriter writer = mock(OutboxWriter.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final OutboxProps props = new OutboxProps(2000L, 50);
    private final OutboxRelay relay = new OutboxRelay(writer, rabbit, props, new ObjectMapper());

    @Test
    @DisplayName("publish-then-mark, in claim order")
    void publishesThenMarksInOrder() {
        OutboxMessage first = message(7L, OutboxEventType.BOOKING_CONFIRMED,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY,
                "{\"bookingId\":1001,\"bookingReference\":\"BK-1\"}");
        OutboxMessage second = message(8L, OutboxEventType.BOOKING_CONFIRMATION_REJECTED,
                RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY,
                "{\"bookingId\":1002,\"paymentId\":500}");
        given(writer.claimBatch(50)).willReturn(List.of(first, second), List.of());

        relay.drain();

        InOrder order = inOrder(rabbit, writer);
        order.verify(rabbit).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY),
                any(Map.class),
                any(MessagePostProcessor.class));
        order.verify(writer).markPublished(first);
        order.verify(rabbit).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.BOOKING_CONFIRMATION_REJECTED_ROUTING_KEY),
                any(Map.class),
                any(MessagePostProcessor.class));
        order.verify(writer).markPublished(second);
        // The loop claims once more to confirm the backlog is empty, then returns.
        verify(writer, times(2)).claimBatch(50);
    }

    @Test
    @DisplayName("the published map carries the outbox row id as eventId for consumer dedupe")
    @SuppressWarnings("unchecked")
    void publishedPayloadCarriesTheOutboxRowId() {
        OutboxMessage row = message(42L, OutboxEventType.BOOKING_CONFIRMED,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "{\"bookingId\":1001}");
        given(writer.claimBatch(anyInt())).willReturn(List.of(row), List.of());

        relay.drain();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(rabbit).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY),
                payload.capture(),
                any(MessagePostProcessor.class));
        assertThat(payload.getValue()).containsEntry("eventId", 42L);
        assertThat(payload.getValue()).containsEntry("bookingId", 1001);
    }

    @Test
    @DisplayName("the same row published twice carries the SAME eventId — the stable dedupe key")
    @SuppressWarnings("unchecked")
    void sameRowRepublishedCarriesTheSameEventId() {
        OutboxMessage row = message(42L, OutboxEventType.BOOKING_CONFIRMED,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "{\"bookingId\":1001}");
        // The same row again on the next claim = a crash between publish and mark, re-published.
        given(writer.claimBatch(anyInt())).willReturn(List.of(row), List.of(row), List.of());

        relay.drain();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(rabbit, times(2)).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY),
                payload.capture(),
                any(MessagePostProcessor.class));
        assertThat(payload.getAllValues())
                .extracting(p -> p.get("eventId"))
                .containsExactly(42L, 42L);
    }

    @Test
    @DisplayName("a row with a persisted trace stamps a W3C traceparent header on the message")
    void rowWithTraceStampsTraceparentHeader() {
        OutboxMessage row = messageWithTrace(7L, OutboxEventType.BOOKING_CONFIRMED,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "{\"bookingId\":1001}",
                "7f3ab9e2c1d44a02b8e1f0c3d5a67890", "a1b2c3d4e5f60718");
        given(writer.claimBatch(anyInt())).willReturn(List.of(row), List.of());

        relay.drain();

        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY),
                any(Map.class),
                processor.capture());
        Message stamped = processor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(stamped.getMessageProperties().getHeaders())
                .containsEntry("traceparent", "00-7f3ab9e2c1d44a02b8e1f0c3d5a67890-a1b2c3d4e5f60718-01");
    }

    @Test
    @DisplayName("a row with no persisted trace publishes WITHOUT a traceparent header (backwards compatible)")
    void rowWithoutTracePublishesNoTraceparentHeader() {
        OutboxMessage row = message(7L, OutboxEventType.BOOKING_CONFIRMED,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "{\"bookingId\":1001}");
        given(writer.claimBatch(anyInt())).willReturn(List.of(row), List.of());

        relay.drain();

        ArgumentCaptor<MessagePostProcessor> processor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(
                eq(RabbitConfig.EXCHANGE),
                eq(RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY),
                any(Map.class),
                processor.capture());
        Message stamped = processor.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertThat(stamped.getMessageProperties().getHeaders()).doesNotContainKey("traceparent");
    }

    @Test
    @DisplayName("broker down: nothing is marked, rows stay pending for the next tick")
    void brokerDownLeavesRowsPending() {
        OutboxMessage row = message(9L, OutboxEventType.BOOKING_CONFIRMED,
                RabbitConfig.BOOKING_CONFIRMED_ROUTING_KEY, "{\"bookingId\":1001}");
        given(writer.claimBatch(anyInt())).willReturn(List.of(row));
        willThrow(new AmqpException("broker down")).given(rabbit)
                .convertAndSend(any(String.class), any(String.class), any(Map.class),
                        any(MessagePostProcessor.class));

        relay.drain(); // must not throw — the next tick retries

        verify(writer, never()).markPublished(any());
    }

    @Test
    @DisplayName("empty backlog: no publish, no mark")
    void emptyBacklogDoesNothing() {
        given(writer.claimBatch(anyInt())).willReturn(List.of());

        relay.drain();

        verify(rabbit, never()).convertAndSend(any(String.class), any(String.class),
                any(Object.class), any(MessagePostProcessor.class));
        verify(writer, never()).markPublished(any());
    }

    /** An outbox row with a fixed id, bypassing the generated-value path unit tests cannot use. */
    private static OutboxMessage message(Long id, OutboxEventType type, String routingKey, String payload) {
        return messageWithTrace(id, type, routingKey, payload, null, null);
    }

    /** A row with a fixed id and the persisted trace context the relay reads back. */
    private static OutboxMessage messageWithTrace(Long id, OutboxEventType type, String routingKey,
            String payload, String traceId, String spanId) {
        OutboxMessage message = new OutboxMessage(type, 500L, routingKey, payload, traceId, spanId);
        try {
            var idField = OutboxMessage.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(message, id);
            return message;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}