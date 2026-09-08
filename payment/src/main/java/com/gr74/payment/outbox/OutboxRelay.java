package com.gr74.payment.outbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.config.OutboxProps;
import com.gr74.payment.config.RabbitConfig;
import com.gr74.payment.service.OutboxWriter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains the transactional outbox to the broker — the second half of the exactly-once-in-effect
 * story (BUILD_PLAN 3.2, see {@code docs/concepts/transactional-outbox.md}).
 *
 * <p>The ordering inside the loop is the guarantee: <b>publish-then-mark</b>. A crash between the
 * two re-publishes on the next tick (safe, because every consumer is idempotent), which makes
 * delivery at-least-once. Mark-then-publish would be lossy: a crash after the mark but before the
 * publish would strand a {@code PaymentSucceeded} no consumer ever sees — and a lost
 * {@code PaymentSucceeded} costs real money, unlike the self-healing {@code MovieUpserted} stream.
 *
 * <p>A broker outage is survivable by construction: the publish throws, the catch defers to the
 * next tick, and the rows simply wait — nothing is marked, nothing is lost. The pending-row count
 * ({@code OutboxMessageRepository.countByPublishedAtIsNull}) is the lag signal worth alerting on.
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
     */
    @Scheduled(fixedDelayString = "${payment.outbox.relay-interval-millis:2000}")
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
                            RabbitConfig.EXCHANGE, message.getRoutingKey(), payloadWithEventId(message));
                    // AFTER the publish — never before. See the class javadoc for why.
                    writer.markPublished(message);
                }
                log.debug("Relay published {} outbox row(s)", batch.size());
            }
        } catch (AmqpException e) {
            // Broker down: rows stay pending and the next tick retries. This is the MovieUpserted
            // lesson fixed — a lost PaymentSucceeded costs real money, so waiting beats dropping.
            log.warn("Outbox relay deferred: broker unavailable — rows stay pending for the next tick", e);
        }
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
