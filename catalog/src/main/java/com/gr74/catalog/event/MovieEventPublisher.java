package com.gr74.catalog.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gr74.catalog.config.RabbitConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends {@link MovieUpserted} to RabbitMQ after the writing transaction commits.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MovieEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMovieUpserted(MovieUpserted event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE, RabbitConfig.MOVIE_UPSERTED_ROUTING_KEY, event);
            log.debug("Published MovieUpserted id={} title='{}' updatedAt={}",
                    event.id(), event.title(), event.updatedAt());
        } catch (RuntimeException e) {
            // The refresh already committed — a broker failure must not fail it.
            log.warn("Failed to publish MovieUpserted id={}; event dropped (self-heals via lazy backfill): {}",
                    event.id(), e.getMessage());
        }
    }
}
