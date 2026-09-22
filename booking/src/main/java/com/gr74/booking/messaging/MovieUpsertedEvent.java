package com.gr74.booking.messaging;

import java.time.Instant;

/**
 * Booking's local copy of the {@code MovieUpserted} event Catalog publishes. It deliberately duplicates
 * Catalog's {@code event.MovieUpserted} record rather than sharing a class: the two services share no jar
 * (database-per-service extends to no shared code), so the wire contract is duplicated on each side — a
 * consumer owns its own view of the message and can evolve it independently (e.g. ignore fields it
 * doesn't need). Fields are matched by name during JSON deserialization.
 *
 * <ul>
 *   <li>{@code id} — the movie id; the UPSERT key into {@code movie_projections}.</li>
 *   <li>{@code title} — the new title to cache.</li>
 *   <li>{@code posterPath} — TMDB's artwork path, cached alongside the title so the ticket email can
 *       render the poster without Notification calling Catalog on the consume path. Nullable.</li>
 *   <li>{@code updatedAt} — the source row's last-modified time; the consumer's ordering-guard baseline
 *       (drop an event whose {@code updatedAt <=} the stored one).</li>
 *   <li>{@code eventId} — <b>intentionally unused until Phase 4.</b> The consumer is idempotent via the
 *       UPSERT, so it needs no dedupe today. Carried so the contract is stable when Phase 4 adds the
 *       {@code processed_events} dedupe (the M4 idempotent-consumer lesson). Not read anywhere yet.</li>
 * </ul>
 */
public record MovieUpsertedEvent(
        Long id,
        String title,
        String posterPath,
        Instant updatedAt,
        String eventId) {
}
