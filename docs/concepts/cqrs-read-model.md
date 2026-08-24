# CQRS read model (event-fed local replica)

## What it is
A **read model** is a service's own **local, queryable copy of *another* service's data**, kept up to
date by consuming that service's events — so the owning service need not be called at read time. It's the
"query" half of **CQRS** (Command Query Responsibility Segregation): writes go to the owner (Catalog owns
movies); reads are served from a projection shaped for the reader (Booking's `movie_titles` cache).

In ScreenMaster it answers the same question as [API composition](database-per-service.md) — *"show me my
bookings, with each movie's title"* — but the other way round. This is **way B** of BUILD_PLAN 2.3 (way A
is composition).

```
Catalog (owns movies)                         Booking (owns bookings)
  refresh a movie ──MovieUpserted──▶ RabbitMQ ──▶ @RabbitListener ──▶ movie_titles (local copy)
                                                                          ▲
  GET /bookings/my?source=readmodel ───────────── local JOIN ────────────┘   (no Catalog call)
```

## Composition vs read model — the trade, in one line
| | API composition (way A) | CQRS read model (way B) |
|---|---|---|
| When the join happens | **at read time**, live over HTTP | **ahead of time**, via events |
| Freshness | always current | **eventually consistent** (lags the event) |
| Catalog down at read time | degrades (null titles) | **still answers** from the local copy |
| Cost | a network call per read | a local table + a consumer to keep it fresh |

Neither is "right" — you pick which failure you'd rather have. A read model buys **read-time decoupling**
(resilience, no N+1 over the network) by **spending consistency** (a rename shows up late).

## Eventual consistency is a decision, not a bug
The local copy lags the real Catalog by however long the event takes to arrive. A movie renamed in Catalog
shows the **old** title in "my bookings (way B)" until the `MovieUpserted` event lands. That staleness is a
**business decision** you accept in exchange for the resilience — say it out loud; don't treat it as a
defect. (See [database-per-service.md](database-per-service.md).)

## The three things that make it correct in ScreenMaster

**1. Idempotent projection (no dedupe table needed here).** The consumer does an UPSERT keyed by the movie
id (an assigned PK). RabbitMQ is at-least-once, so a redelivered event just re-writes the same row — no
duplicate, no `processed_events` table. That table earns its place only when the side effect is *not*
naturally idempotent (Phase 4's Notification "sends an email") — see
[idempotent-consumer.md](idempotent-consumer.md).

**2. The ordering guard.** RabbitMQ doesn't guarantee global order across redeliveries, so an *older* event
could arrive after a newer one. The consumer drops any event whose `updatedAt` is `<=` the stored one, so a
late/duplicate old message can't overwrite a fresher cached title. The guard only works because `updatedAt`
is the **source row's `@LastModifiedDate`** (Catalog's clock, per-row monotonic) — **not** a publish-time
stamp, which would carry a *newer* time on a redelivered *older* event and defeat the guard.

**3. Lazy backfill seeds the cold start.** The publisher fires **only from the incremental-refresh path,
never from backfill** (see [ADR 0001](../adr/0001-movieupserted-published-from-incremental-refresh-only.md)),
so on a fresh system the stream may be silent for a while and `movie_titles` starts empty. On a cache miss
the read model fetches the one title from Catalog and caches it, then serves local forever after. Two
consequences to state honestly:
- Way B is **network-free only for already-cached movies** — a cold movie still costs one Catalog touch.
  The "Catalog down, way B still works" demo therefore reads a movie **once while Catalog is up** (filling
  the cache), *then* stops Catalog.
- The backfill must **not poison the cache**: on a miss it distinguishes "no such movie" (404 → cache
  nothing) from "Catalog unavailable" (throw → write **nothing**, serve null this once, retry next read).
  Writing a placeholder on an outage would hide the miss and suppress the retry.

## Reliability gap (named, deferred)
The publisher does a **direct publish after commit** (`@TransactionalEventListener(AFTER_COMMIT)`), so it
never announces a rolled-back change — but if the broker is down at that instant the event is **lost**. The
loss self-heals (lazy backfill fills the title on the next miss; the next refresh re-emits), and it's fully
closed by the [transactional outbox](transactional-outbox.md) in Phase 4. We ship the naive version here so
M4's outbox has a felt motivation.

## Where it lives in the code
- **Publisher** — `catalog`: `event/MovieUpserted.java`, `event/MovieEventPublisher.java`
  (`@TransactionalEventListener`), raised from `service/CatalogUpserter.refreshMovies`, `config/RabbitConfig`.
- **Consumer + read model** — `booking`: `messaging/MovieUpsertedListener.java` (thin adapter) →
  `service/MovieTitleProjector.java` (the guarded idempotent UPSERT), `model/MovieTitle.java`,
  `repository/MovieTitleRepository.java`, changeset `005-create-movie-titles.yaml`, `config/RabbitConfig`.
- **Read path** — `booking`: `service/MovieTitleReadModel.java` (local join + lazy backfill),
  `service/MyBookingsService.java` (branches on `TitleSource`), `GET /bookings/my?source=readmodel`.

## Interview lens
"For a cross-service query I can compose at read time or keep a read model. The read model is a local
event-fed replica — I keep a movie-title cache in Booking, updated by a `MovieUpserted` event, so 'my
bookings' joins locally and still answers when Catalog is down. The price is eventual consistency; I made
the consumer idempotent by upserting on the movie id, guarded out-of-order writes with the source row's
last-modified timestamp, and seeded cold-start misses with a lazy backfill that's careful not to cache an
outage. The direct publish can drop an event on a broker hiccup — that's exactly what the outbox fixes
next."

## Related
[database-per-service.md](database-per-service.md) ·
[rabbitmq.md](rabbitmq.md) ·
[idempotent-consumer.md](idempotent-consumer.md) ·
[sync-vs-async-comms.md](sync-vs-async-comms.md) ·
[transactional-outbox.md](transactional-outbox.md) ·
[ADR 0001](../adr/0001-movieupserted-published-from-incremental-refresh-only.md)
