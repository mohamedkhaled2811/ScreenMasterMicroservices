package com.gr74.booking.outbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.booking.config.OutboxProps;
import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.OutboxWriter;

import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains the transactional outbox to the broker — the second half of the exactly-once-in-effect
 * story (BUILD_PLAN 4.1/4.2, see {@code docs/concepts/transactional-outbox.md}).
 *
 * <p>The ordering inside the loop is the guarantee: <b>publish-then-mark</b>. A crash between the
 * two re-publishes on the next tick (safe, because every consumer is idempotent), which makes
 * delivery at-least-once. Mark-then-publish would be lossy: a crash after the mark but before the
 * publish would strand a {@code BookingConfirmationRejected} no consumer ever sees — and that is
 * the exact loss Phase 3 carried: a stranded refund. The confirmed path self-heals (the booking
 * still reads CONFIRMED, a backfill reconciles it); the rejected path has no such backstop.
 *
 * <p>A broker outage is survivable by construction: the publish throws, the catch defers to the
 * next tick, and the rows simply wait — nothing is marked, nothing is lost. The pending-row count
 * ({@code OutboxMessageRepository.countByPublishedAtIsNull}) is the lag signal worth alerting on.
 *
 * <p>Mirrors {@code payment}'s {@code OutboxRelay} file-for-file — the same deliberate duplication
 * as the routing-key constants (no shared module).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxWriter writer;
    private final RabbitTemplate rabbit;
    private final OutboxProps props;
    private final ObjectMapper objectMapper;

    /**
     * Drain pending rows until the backlog is empty. Claims in bounded batches
     * ({@code SKIP LOCKED}, so overlapping ticks or instances never block on each other) and walks
     * the backlog over several passes after a long outage rather than in one enormous loop.
     *
     * <p>{@code @Observed} (Phase 6, decision D2): one span per drain tick, so a growing backlog —
     * the "why is the email 10 minutes late" question — is visible in Zipkin as one timed unit.
     * This span is a fresh trace by design: it runs on a scheduler thread with no request context,
     * which is exactly why the rows carry a persisted trace (see {@code 011-add-outbox-trace-context.yaml}).
     */
    @Observed(name = "outbox.drain", contextualName = "drain-outbox")
    @Scheduled(fixedDelayString = "${booking.outbox.relay-interval-millis:2000}")
    void drain() {
        try {
            while (true) {
                List<OutboxMessage> batch = writer.claimBatch(props.relayBatchSize());
                if (batch.isEmpty()) {
                    return;
                }
                for (OutboxMessage message : batch) {
                    // The wire eventId is the outbox row id: stable across redeliveries, so the
                    // consumer can dedupe a republished event onto the same id.
                    rabbit.convertAndSend(
                            RabbitConfig.EXCHANGE, message.getRoutingKey(), payloadWithEventId(message),
                            traceparent(message));
                    // AFTER the publish — never before. See the class javadoc for why.
                    writer.markPublished(message);
                }
                log.debug("Relay published {} outbox row(s)", batch.size());
            }
        } catch (AmqpException e) {
            // Broker down: rows stay pending and the next tick retries. This closes the Phase-3 gap
            // BookingEventPublisher named — a lost BookingConfirmationRejected strands a refund, so
            // waiting beats dropping.
            log.warn("Outbox relay deferred: broker unavailable — rows stay pending for the next tick", e);
        }
    }

    /**
     * A {@link MessagePostProcessor} that stamps the persisted trace context onto the outgoing AMQP
     * message as a W3C {@code traceparent} header — the whole Phase-6 trick (decision B1).
     *
     * <p>The trace is not live on this scheduler thread (that is why it was persisted), so this is
     * the only way the consumer can re-join the original trace. The stored span id becomes the
     * header's parent span id, so the consumer's restored span is a child of the span that caused
     * the event. A row with no persisted trace (written before Phase 6, or genuinely trace-less)
     * publishes with no header — exactly the behaviour every pre-Phase-6 consumer already expected.
     */
    private MessagePostProcessor traceparent(OutboxMessage message) {
        String traceId = message.getTraceId();
        String spanId = message.getSpanId();
        if (traceId == null || spanId == null) {
            return m -> m;
        }
        String header = "00-" + traceId + "-" + spanId + "-01";
        return m -> {
            m.getMessageProperties().setHeader("traceparent", header);
            return m;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadWithEventId(OutboxMessage message) {
        try {
            Map<String, Object> payload =
                    objectMapper.readValue(message.getPayload(), LinkedHashMap.class);
            payload.put("eventId", message.getId());
            return payload;
        } catch (Exception e) {
            // Our own serialization wrote this JSON minutes ago; if it no longer parses, something
            // is structurally wrong and failing the tick loudly beats silently skipping the row.
            throw new IllegalStateException(
                    "Outbox row " + message.getId() + " holds unparseable payload", e);
        }
    }
}