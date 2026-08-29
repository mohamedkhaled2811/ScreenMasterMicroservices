# Booking · Cross-Service Reads

**Type:** high-level (process / swimlane) · **Scope:** the `booking` service and its edges to `catalog`
**Files:** `process-booking-integration.html` (source) · `.svg` · `.png`

## What it shows

Every place Booking crosses the service boundary, and the two different answers it gives to
"the JOIN is gone" — API composition and a CQRS read model.

Booking holds **no FK into Catalog**. The monolith's `booking → showtime → movie` JOIN is cut, so
every movie title now travels over HTTP or over AMQP. The diagram walks that in seven steps.

## The three synchronous Catalog calls

They deliberately answer failure differently — see `CatalogClient`'s javadoc.

| Step | Call | On 404 | On outage |
|---|---|---|---|
| 1 · VALIDATE | `verifyMovieExists` → `GET /movies/{id}` | reject the write (404) | reject the write (503) |
| 5 · WAY A | `titlesByIds` → `GET /movies/batch?ids=…` | id absent from map | degrade to empty map, `movieTitle: null` |
| 6 · WAY B | `titleById` → `GET /movies/{id}` (backfill only) | `Optional.empty()`, safe to serve null | **throws** — must not poison the cache |

A write must reject an unverifiable id; a read is more useful partial than absent; a cache-fill has to
tell the two apart because it *persists* the answer.

## The fork (step 4, accent)

`BookingService.resolveTitles()` switches on `?source=`:

- **way A — composition** (step 5): one batched Catalog call for the whole page. Fresh, but a Catalog
  outage means null titles. Batching is what keeps this off the network N+1.
- **way B — read model** (step 6): a local `WHERE id IN (…)` against `movie_projections`. Survives a Catalog
  outage for already-cached movies, at the cost of eventual consistency. A genuine miss triggers one
  lazy backfill (dashed) in a `REQUIRES_NEW` transaction — the read path runs `readOnly`, so the write
  is delegated to `MovieBackfiller` or it would be silently dropped.

## The CQRS write side (step 7)

Catalog publishes `MovieUpserted` to the shared topic exchange knowing nothing about consumers.
Booking owns its queue and binding; `MovieProjector` applies the event — idempotent by PK
(so at-least-once redelivery is harmless) and guarded on `updatedAt` (so a late event can't overwrite
a fresher title).

## Deliberately out of scope

The gateway and Eureka `lb://` resolution, the seat-hold/pricing internals of `POST /bookings`, and
payment/notification — the latter aren't wired to Booking yet.

## Regenerating

```bash
# PNG (needs playwright + chromium)
python raster.py process-booking-integration.html process-booking-integration.png 2
```

> **Rendered assets are stale.** The committed `.svg`/`.png` still show the pre-rename names
> (`movie_titles`, `MovieProjector` was `MovieTitleProjector`, `BookingService` was `MyBookingsService`).
> The prose *and the `.html` source* are current — only the exported images need regenerating.
