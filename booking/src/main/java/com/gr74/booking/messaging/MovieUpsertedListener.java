package com.gr74.booking.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.MovieProjector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The AMQP adapter for the {@code MovieUpserted} stream — a deliberately thin shell over
 * {@link MovieProjector}. All the interesting behaviour (the idempotent UPSERT and the ordering
 * guard) lives in the projector so it is unit-testable without a broker; this class only bridges the
 * queue to it.
 *
 * <p>The message body deserializes into a {@link MovieUpsertedEvent} via the JSON converter wired in
 * {@link RabbitConfig}. An exception thrown here (e.g. the DB is momentarily down) propagates, the message
 * is <em>not</em> acked, and RabbitMQ redelivers it — safe precisely because the projector is idempotent.
 *
 * <p><b>Tracing contrast (Phase 6):</b> this consumer does NOT restore a {@code traceparent} header.
 * Catalog publishes {@code MovieUpserted} DIRECTLY at AFTER_COMMIT from the request thread, where the
 * trace is still live — so the context rides along in the message headers and propagates
 * automatically. That is exactly the hop that does not need manual help, in deliberate contrast to
 * {@code PaymentEventListener} / Notification's {@code BookingEventListener}, whose messages went
 * through the transactional outbox (publish on a scheduler thread, context long gone) and must be
 * restored from the persisted trace. Both shapes in one system is the teaching point of the phase.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MovieUpsertedListener {

    private final MovieProjector projector;

    @RabbitListener(queues = RabbitConfig.MOVIE_PROJECTIONS_QUEUE)
    public void onMovieUpserted(MovieUpsertedEvent event) {
        log.debug("Received MovieUpserted id={} title='{}'", event.id(), event.title());
        projector.apply(event);
    }
}
