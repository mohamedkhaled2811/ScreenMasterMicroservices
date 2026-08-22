# 1. `MovieUpserted` is published from the incremental-refresh path only, not backfill

Date: 2026-08-03

## Status

Accepted

## Context

Way B of "my bookings" (BUILD_PLAN 2.3) gives Booking a local **read model** — a
`movie_titles` title cache — kept fresh by consuming a `MovieUpserted` event that
Catalog publishes when it writes a movie row.

Catalog writes movie rows in two very different situations, both routed through the
same `CatalogUpserter.hydrateOne` → `movieRepository.save(...)`:

1. **Backfill** (`upsertPage`) — the initial bulk load that walks TMDB
   `POPULAR`/`TOP_RATED`/`NOW_PLAYING` and hydrates the whole catalog (hundreds to
   thousands of movies) page by page on first runs.
2. **Incremental refresh** (`refreshMovies`) — runs *after* backfill completes,
   re-hydrating only the ids that TMDB's `/movie/changes` feed reports as changed in
   a bounded recent window.

If the publish fired on **every** hydrate, the first backfill would emit one
`MovieUpserted` per movie in the entire catalog — a broker flood at cold start, and
Booking would churn its `movie_titles` table for movies no one has a booking for. That
teaches nothing except "don't do that."

We chose (grilling session, 2026-08-03) to define `MovieUpserted` as the publisher's
truth — "Catalog wrote this movie row" (event option A) — but to **emit it only from
the incremental-refresh path**.

## Decision

`MovieUpserted` is published **only** from `CatalogUpserter.refreshMovies`
(the incremental-refresh path), never from `upsertPage` (backfill).

The event therefore means: **"a movie changed while Booking was already running."**
Backfill is treated as a bulk data-load concern, not part of the event stream.

Booking's read model is seeded instead by **lazy backfill on cache miss** (see the
way-B plan, decision 4C-A): a `movie_titles` miss triggers a one-time
`CatalogClient.titleById` fetch that caches the title locally.

## Consequences

- **Backfill stays silent** — no broker flood on first boot; the stream carries only
  genuine post-launch changes.
- **The read model is empty on a fresh `docker compose up`** until either (a) a real
  upstream change fires a refresh event, or (b) a booking read lazily backfills a title.
  In a fresh demo with no upstream changes, **lazy backfill is the only thing that fills
  the cache** — which makes decision 4C-A (lazy backfill) load-bearing, not optional.
- **A future reader will be surprised** that the read model doesn't fill itself purely
  from events on a clean clone. This ADR is why. The events are the *steady-state
  freshness* mechanism; lazy backfill is the *cold-start seeding* mechanism. Both are
  required; neither alone is sufficient.
- **The 2.4 "break it" demo must read once while Catalog is up** (to fill the cache via
  lazy backfill) **before** stopping Catalog — way B answers from cache only for
  already-cached movies.
- Trade-off accepted: we lose "the read model reconstructs itself entirely from the
  event log." In exchange we avoid a cold-start flood and keep the event stream meaning
  "real change," which is the more useful signal. Full log-replay reconstruction is out
  of scope for this learning milestone.

## Related

- Plan: `docs/plans/2026-07-01-booking-my-bookings-way-b.html`
- Glossary: `CONTEXT.md` — *MovieUpserted*, *Read model*, *Title cache*
- Concepts: `docs/concepts/cqrs-read-model.md` (to be written with 2.3),
  `docs/concepts/transactional-outbox.md` (Phase 4 closes the lost-event gap)
