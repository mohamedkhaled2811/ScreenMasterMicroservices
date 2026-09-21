package com.gr74.booking.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.messaging.MovieUpsertedEvent;
import com.gr74.booking.model.MovieProjection;
import com.gr74.booking.repository.MovieProjectionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies a {@code MovieUpserted} event to Booking's {@code movie_projections} read model — the guarded,
 * idempotent write side of way B. Kept a plain {@code @Transactional} service (not the {@code @RabbitListener}
 * itself) so it can be unit-tested by direct invocation, and so the AMQP adapter stays a thin shell.
 *
 * <p><b>Idempotent by the key.</b> The PK is the assigned movie id, so applying the same event twice just
 * re-writes the same row — a redelivery (RabbitMQ is at-least-once; a consumer that dies before ack gets
 * the message again) can't duplicate anything. That is why no {@code processed_events} dedupe table is
 * needed here (contrast Phase 4's Notification, whose side effect — sending an email — is <em>not</em>
 * naturally idempotent, which is where that table earns its place).
 *
 * <p><b>Ordering guard.</b> RabbitMQ does not guarantee global ordering across redeliveries, so an
 * <em>older</em> event could arrive after a newer one. We drop any event whose {@code updatedAt} is not
 * strictly newer than what we already stored, so a late/duplicate old event can never overwrite a fresher
 * cached title. The comparison uses the source row's last-modified time carried on the event (Catalog's
 * clock, per-row monotonic) — see {@code docs/concepts/cqrs-read-model.md}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieProjector {

    private final MovieProjectionRepository movieProjectionRepository;

    /**
     * Upsert the title for {@code event.id()}, unless a strictly-newer (or equal) row is already stored.
     *
     * @return {@code true} if the read model was written, {@code false} if the event was dropped as stale
     */
    @Transactional
    public boolean apply(MovieUpsertedEvent event) {
        MovieProjection existing = movieProjectionRepository.findById(event.id()).orElse(null);

        if (existing != null && !isNewer(event.updatedAt(), existing.getUpdatedAt())) {
            log.debug("Dropping stale MovieUpserted id={} (event updatedAt={} <= stored {})",
                    event.id(), event.updatedAt(), existing.getUpdatedAt());
            return false;
        }

        if (existing == null) {
            movieProjectionRepository.save(new MovieProjection(
                    event.id(), event.title(), event.posterPath(), event.updatedAt()));
        } else {
            existing.apply(event.title(), event.posterPath(), event.updatedAt());
            movieProjectionRepository.save(existing);
        }
        log.debug("Applied MovieUpserted id={} title='{}' updatedAt={}",
                event.id(), event.title(), event.updatedAt());
        return true;
    }

    /**
     * Is {@code incoming} strictly newer than {@code stored}? A {@code null} stored timestamp is a
     * lazy-backfilled row with no baseline — any real event wins. A {@code null} incoming timestamp
     * (not expected from a real event) is treated as not-newer, so it never clobbers a timestamped row.
     */
    private static boolean isNewer(Instant incoming, Instant stored) {
        if (stored == null) {
            return true;
        }
        if (incoming == null) {
            return false;
        }
        return incoming.isAfter(stored);
    }
}
