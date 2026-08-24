package com.gr74.catalog.event;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.gr74.catalog.config.RabbitConfig;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends {@link MovieUpserted} to RabbitMQ — the broker half of the publisher, split out from
 * {@link com.gr74.catalog.service.CatalogUpserter} on purpose.
 *
 * <p><b>Why an {@code @TransactionalEventListener(AFTER_COMMIT)} and not a plain {@code convertAndSend}
 * in the upsert?</b> The upsert runs in a transaction. If we published to the broker <em>inside</em> it
 * and the transaction then rolled back, we'd have announced a change that never happened — a phantom
 * event the read model would apply. Raising an in-JVM application event inside the txn and doing the
 * broker send only once the commit succeeds means the event fires <b>if and only if</b> the row is
 * durably written. (This is the read-your-writes ordering; it does <em>not</em> make the publish
 * itself reliable — if the broker is down at {@code AFTER_COMMIT} the event is still lost. That gap is
 * the exact motivation for the Phase-4 transactional outbox, and we name it rather than hide it. See
 * {@code docs/concepts/transactional-outbox.md} and {@code docs/concepts/cqrs-read-model.md}.)
 *
 * <p>A failure to publish here is logged and swallowed: it must not roll back the already-committed
 * refresh (the transaction is over), and the lost update self-heals — Booking's lazy backfill fills the
 * title on the next cache miss, and the next refresh re-emits.
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
            // The refresh already committed — don't let a broker hiccup fail it. The lost event self-heals
            // (lazy backfill on the consumer + the next refresh re-emits); the outbox (Phase 4) removes it.
            log.warn("Failed to publish MovieUpserted id={}; event dropped (self-heals via lazy backfill): {}",
                    event.id(), e.getMessage());
        }
    }
}
