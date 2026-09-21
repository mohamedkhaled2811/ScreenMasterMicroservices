package com.gr74.catalog.event;

import java.time.Instant;

/**
 * The event Catalog publishes when it <em>writes a movie row on the incremental-refresh path</em> — a
 * movie changed upstream (TMDB) after we were already running, and we re-hydrated it. It is the
 * publisher's truth ("Catalog wrote this movie"), <b>not</b> a narrow "the title changed" — see
 * {@code CONTEXT.md} → <i>MovieUpserted</i> and {@code docs/adr/0001-...}.
 *
 * <p><b>It is emitted only from {@code CatalogUpserter.refreshMovies}, never from backfill</b>
 * ({@code upsertPage}). Backfill is a bulk load; flooding the broker with one event per movie in the
 * whole catalogue on first boot teaches nothing and junks the consumer's cache. The event stream means
 * "a movie moved while Booking was live". Booking seeds its read model by lazy backfill on a cache miss,
 * not by replaying this stream. (ADR 0001.)
 *
 * <p>Fields, and what each is <em>for</em> (a payload field that no consumer reads is a lie in the
 * contract, so every field here has a job):
 * <ul>
 *   <li>{@code id} — the movie id; the consumer's UPSERT key. The whole point.</li>
 *   <li>{@code title} — the new title; what Booking caches.</li>
 *   <li>{@code posterPath} — TMDB's artwork path ("/abc123.jpg"), cached alongside the title. Added
 *       because Notification's ticket email renders the movie's poster, and the only way it can do
 *       that WITHOUT a synchronous Catalog call on the consume path is for Booking to already hold
 *       the value and snapshot it onto {@code BookingConfirmed}. Note it is the PATH, not a URL: the
 *       CDN host and image size are rendering decisions the consumer composes, so changing image
 *       width never means re-publishing the catalogue. Nullable — TMDB has no artwork for some
 *       titles.</li>
 *   <li>{@code updatedAt} — the movie row's {@code @LastModifiedDate} in <em>Catalog's</em> clock
 *       (per-row monotonic, one clock, bumps on every write). The consumer enforces an ordering guard
 *       with it: an event whose {@code updatedAt} is {@code <=} the stored one is dropped, so a
 *       redelivered <em>older</em> event can never overwrite a newer cached title. It is the row's
 *       last-modified time, NOT a publish-time stamp — a publish-time stamp would break the guard under
 *       exactly the redelivery it exists to defend (an old event could carry a newer time). See ADR 0001
 *       / the grill notes.</li>
 *   <li>{@code eventId} — a per-emit UUID. <b>Intentionally carried but not yet consumed.</b> Booking's
 *       consumer is naturally idempotent via the UPSERT, so it needs no dedupe table today. {@code eventId}
 *       is on the wire now so the event contract is stable when Phase 4 adds the {@code processed_events}
 *       dedupe (the M4 idempotent-consumer lesson) — no schema bump then. Until Phase 4 nothing reads it.</li>
 * </ul>
 *
 * <p>Serialized as JSON ({@code Jackson2JsonMessageConverter}); a {@code record} keeps it an immutable
 * DTO that crosses the wire (never an entity).
 */
public record MovieUpserted(
        Long id,
        String title,
        String posterPath,
        Instant updatedAt,
        String eventId) {
}
