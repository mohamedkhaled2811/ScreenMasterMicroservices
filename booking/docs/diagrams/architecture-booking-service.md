# Booking · Service Architecture

**Type:** high-level (architecture) · **Scope:** the `booking` service, end to end
**Files:** `architecture-booking-service.html` (source) · `.svg`

## What it shows

What the Booking service **is** and what it **does** — the structural companion to
`process-booking-integration`, which covers only the cross-service reads. Read this one first for
orientation, then that one for the depth on Catalog.

Booking is the service that absorbed **three bounded contexts** from the monolith. The diagram groups
them, hangs each off the tables it owns, and shows the two edges to the outside world.

## The three contexts (top zone)

| Context | Endpoints | What it owns |
|---|---|---|
| **Inventory** (Theater) | `/theaters`, `/theaters/{id}/screens`, `/screens/{id}/seats`, `/seat-types` | the physical room: theaters → screens → seats → seat types |
| **Scheduling** (Showtime) | `/showtimes`, `/showtimes/movie/{id}`, `/showtimes/screen/{id}` | when a movie plays in a screen, at what base price |
| **Booking** | `POST /bookings`, `GET /bookings/my` | the reservation itself + its frozen line items |

The **inventory** listings are paginated and filtered by a composed JPA `Specification` — the repo-wide
rule that listings never dump. The seat-grid generator (`POST /screens/{id}/seats/grid`) is the one bulk
write.


## The focal node: `POST /bookings`

Coral because it is the service's **core domain write** and the only place a real invariant is defended.
`BookingService.create` runs six ordered guards:

1. showtime must exist — it carries the screen, the `movieId`, and the `basePrice`
2. every requested seat id must resolve (input de-duped first)
3. every seat must sit on **this showtime's screen** — no reserving a seat from another room
4. **double-booking guard** — reject if any seat is already held for this showtime
   (`PENDING`/`CONFIRMED`), backstopped by `uq_booking_seats_booking_seat`
5. **price derived server-side** — `showtime.basePrice × seatType.priceMultiplier`, frozen onto each
   line item. A client never names its own price.
6. persist `PENDING` with a 15-minute hold (`expiresAt`)

Deliberately **not** here: payment and compensation. That orchestration is the Phase-3 saga, which will
wrap this create rather than replace it.

## The two edges

- **Synchronous → Catalog** (blue). `CatalogClient` over `lb://catalog`, three call sites with three
  *different* failure contracts — the write path rejects, the read path degrades, the cache-fill
  distinguishes 404 from outage. Full breakdown in `process-booking-integration.md`.
- **Asynchronous ← RabbitMQ** (dashed). `MovieUpsertedListener` → `MovieProjector` keeps the local
  `movie_projections` read model current; idempotent by PK, guarded on `updatedAt`.

`GET /bookings/my` forks between them on `?source=` — way A composes live from Catalog, way B joins
locally. That fork is the M2 lesson and is drawn in detail in the integration diagram.

## The data zone

One database, `booking-db`, Liquibase-migrated with `ddl-auto=validate`. The label worth reading is
**no FK to catalog** — the monolith's `booking → showtime → movie` JOIN is cut. `movieId` is a snapshotted
id, and `movie_projections` is a *cache Booking owns*, not a shared table.

## Icons

| Icon | Where | Style | Source |
|---|---|---|---|
| theater · screen · seat | Inventory node, left to right with `>` chevrons | stroked | **custom** |
| showtime (calendar + clock) | Scheduling node, top-right | stroked | **custom** |
| catalog (film strip) | Catalog node, top-right | stroked | **custom** |
| PostgreSQL | `booking-db`, `movie_projections`, legend | filled | Simple Icons (CC0) |
| RabbitMQ | RabbitMQ node, legend | filled | Simple Icons (CC0) |

The five domain icons are **not** from the skill's icon library — that set is IT/cloud only (server,
database, queue, k8s, …) and has no cinema, calendar, or film glyph. They were drawn to match its house
style exactly: 24×24 viewBox, `fill="none"`, `stroke="currentColor"`, `stroke-width="1.5"`, round caps
and joins — the same Tabler idiom as the stock `database` glyph, so they sit beside it without looking
foreign.

Why these shapes: a **showtime** is a date *and* a time, so the calendar carries a clock — the two fields
(`show_date`, `show_time`) that make up `uq_showtimes_slot`. **Catalog** is a film strip because the one
thing it owns that Booking cares about is movies.

All four are `<symbol>` definitions in `<defs>`, placed with `<use>` and coloured via `color=` (they
inherit through `currentColor`). To recolour one, change the `color` attribute on its `<use>` — never the
symbol.

The theater → screen → seat chevrons encode **containment**, matching the FK chain in `001-create-inventory.yaml`:
a theater has screens, a screen has seats.


## Deliberately out of scope

The gateway and Eureka `lb://` resolution; the DTO / `Specification` / `ProblemDetail` plumbing (folded
into node sublabels); the step-by-step internals of `POST /bookings` (listed above as prose instead —
they would need their own flowchart); payment and notification, which aren't wired to Booking yet.

## Regenerating

The `.svg` is extracted from the HTML (first `<svg>` node, with a Google Fonts `@import` injected and
`&` XML-escaped).

A `.png` was **not** generated — this checkout has no rasterizer (`raster.py` referenced by the sibling
sidecar is not committed, and Playwright/Chromium are absent). To add one:

```bash
pip install playwright && playwright install chromium
```

Then re-run the export. The `.html` and `.svg` render correctly in any modern browser as-is.

> **Rendered assets are stale.** The committed `.svg`/`.png` still show the pre-rename names
> (`movie_titles`, `MovieProjector` was `MovieTitleProjector`, `BookingService` was `MyBookingsService`).
> The prose *and the `.html` source* are current — only the exported images need regenerating.
