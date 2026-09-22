package com.gr74.booking.messaging;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.gr74.booking.config.RabbitConfig;
import com.gr74.booking.service.MovieProjector;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AMQP adapter forwarding {@code MovieUpserted} events to the projector.
 * Unacked failures redeliver; safe because the projector is idempotent.
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
