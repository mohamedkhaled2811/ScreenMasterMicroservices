package com.gr74.booking.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.MovieTitleProjector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The AMQP adapter for the {@code MovieUpserted} stream — a deliberately thin shell over
 * {@link MovieTitleProjector}. All the interesting behaviour (the idempotent UPSERT and the ordering
 * guard) lives in the projector so it is unit-testable without a broker; this class only bridges the
 * queue to it.
 *
 * <p>The message body deserializes into a {@link MovieUpsertedEvent} via the JSON converter wired in
 * {@link RabbitConfig}. An exception thrown here (e.g. the DB is momentarily down) propagates, the message
 * is <em>not</em> acked, and RabbitMQ redelivers it — safe precisely because the projector is idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MovieUpsertedListener {

    private final MovieTitleProjector projector;

    @RabbitListener(queues = RabbitConfig.MOVIE_TITLES_QUEUE)
    public void onMovieUpserted(MovieUpsertedEvent event) {
        log.debug("Received MovieUpserted id={} title='{}'", event.id(), event.title());
        projector.apply(event);
    }
}
