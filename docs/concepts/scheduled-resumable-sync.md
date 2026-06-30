# Scheduled, resumable sync

## What it is
A background job that **periodically pulls data from an external system** (here: TMDB → Catalog) and is **resumable** — it records how far it got, so a crash, restart, or rate-limit pause continues the walk instead of starting over. Two ingredients: a `@Scheduled` trigger (the *when*) and a small bookkeeping table (the *where I left off*).

## Why it exists
Owning your data in a microservice means owning its **ingestion**. In the monolith, movies were just rows other code JOINed to; as a service, Catalog must independently keep its own database fresh from TMDB. And the outside world is unreliable: TMDB rate-limits, times out, returns one page at a time across hundreds of pages. A naive "fetch everything in one loop" job that dies on page 213 either re-fetches 1–212 every run (wasteful, hammers the rate limit) or loses its place. The fix is to **persist progress** and bound how much each run does.

## The mechanics

**1. A bookkeeping row per work stream.** Catalog keeps a `sync_status` row per `SyncType` (`POPULAR`/`TOP_RATED`/`NOW_PLAYING`):

```
sync_type (unique) | last_page | total_pages | state         | error_message | last_synced_at
POPULAR            | 7         | 500         | RUNNING       | null          | ...
TOP_RATED          | 100       | 100         | COMPLETED     | null          | ...
NOW_PLAYING        | 12        | 40          | FAILED        | "503 on p.13" | ...
```

`last_page` is the resume cursor; `total_pages` is learned from the first response; `state` + `error_message` are observability.

**2. Each page commits in its own transaction.** This is the crux. The per-page upsert *and* the cursor advance happen in **one transaction** (`CatalogUpserter.upsertPage`). So after page 7 commits, the cursor durably says "7 done" — a crash on page 8 can't lose page 7's work or its bookmark. The orchestrator (`TmdbSyncService`) is deliberately **not** `@Transactional`: a single giant transaction around the whole walk would roll *everything* back on the last page's failure, defeating resumability.

> **Spring gotcha (self-invocation):** `@Transactional` only applies when the call goes *through* the Spring proxy. If the orchestrator called its own `@Transactional` method (`this.upsertPage(...)`), the proxy is bypassed and the per-page transaction silently vanishes. That's why the transactional writes live on a **separate bean** (`CatalogUpserter`) the orchestrator injects — every call crosses the proxy. See [jpa-and-hibernate.md](jpa-and-hibernate.md) (`@Transactional`).

**3. Bound the work per run.** Each tick walks at most `maxPagesPerRun` pages (our own back-pressure against the rate limit), then stops; the next tick resumes from `last_page + 1`. A type whose `last_page >= total_pages` is `COMPLETED` and skipped — so once filled, the hourly tick is nearly free.

**4. Failure resumes, doesn't corrupt.** On any error the row is marked `FAILED` at the last *good* page; the next tick picks up from there. One unfetchable movie is logged and skipped (the page still advances) so a single bad id can't wedge the whole list.

## Idempotency makes re-runs safe
A resumable sync re-touches rows (a healed retry, a periodic refresh). That's only safe if writes are **idempotent**. Catalog gets this for free: movie/genre PKs are **assigned TMDB ids**, so `repository.save(movie)` on an existing id is an UPDATE, not a duplicate INSERT. Re-syncing the same page converges to the same rows. (Same lesson as the [idempotent-consumer](idempotent-consumer.md), here via assigned-key UPSERT instead of a dedupe table.)

## Backfill is not freshness — the incremental refresh
The page-walk above is a **one-time backfill**: it fills the catalog, then each `COMPLETED` type is skipped forever. That's the right shape for *getting* the data, but it leaves the replica **frozen** — a movie's rating, popularity, overview or poster can change on TMDB and we'd never see it. Re-walking the ranked lists doesn't fix this: `popular`/`top_rated` re-rank constantly, so a movie whose details changed may have moved off the page we'd re-read, and most edits happen to movies on no list at all.

The production answer is a **change feed**, not a re-scan. TMDB exposes `GET /movie/changes?start_date=&end_date=` — the ids of movies edited in a UTC date window. So once backfill is complete, each tick:

1. asks TMDB *"which movie ids changed since we last caught up?"* (one narrow day window at a time),
2. **intersects with the ids we already store** (`MovieRepository.findExistingIds`) — we never re-fetch TMDB's millions of untracked movies, only our own,
3. re-hydrates just those via the same idempotent upsert, and
4. advances a **date cursor** — `last_changes_synced_date` on a dedicated `CHANGES` `sync_status` row — one day at a time, so a crash mid-window resumes from the last good day.

This reuses every mechanism the backfill already had (per-batch transaction, assigned-key idempotent upsert, per-run cap), swapping the *page* cursor for a *date* cursor. Two costs are inherent and worth naming:

- **Bounded history.** TMDB serves only ~14 days of changes (`changes-lookback-days`, clamped). If the service is down longer than that, the cursor can't close the gap from the feed alone — a known limitation; a periodic full re-walk would be the backstop (out of scope here).
- **At-least-once, not exactly-once.** Overlapping or retried day windows can re-fetch an id. Harmless, because the upsert keys on the TMDB id → UPDATE, never duplicate.

## How we use it here (Catalog data load)
- `TmdbScheduledTasks` — the `@Scheduled(cron = "${tmdb.cron}")` trigger, guarded by `@ConditionalOnProperty("tmdb.enabled")` so it doesn't exist (and never calls TMDB) in tests or when no token is configured.
- `TmdbSyncService.syncAll()` — refreshes genres, walks each *backfill* `SyncType` up to `maxPagesPerRun` pages (isolating per-type failures), then — once all backfill lists are `COMPLETED` and `changes-enabled` — runs `syncChanges()` (the incremental refresh).
- `CatalogUpserter` — the `@Transactional` writer: `upsertPage` (backfill, movies + page cursor in one commit) and `refreshMovies` (incremental, refreshed movies + date cursor in one commit).
- `TmdbApiClient` — the thin RestClient wrapper that talks to TMDB and maps wire DTOs → entities (`listPage`, `movieDetails`, `changedMovieIds`); the only thing tests mock, so the sync is unit-testable with no network. See [spring-web-annotations.md](spring-web-annotations.md) (`RestClient`).
- `SyncStatus` / `sync_status` — the bookkeeping entity. The three list rows use the page cursor (`last_page`/`total_pages`); the `CHANGES` row uses the date cursor (`last_changes_synced_date`). Liquibase changeset 002 (table) + 003 (date-cursor column). Enums persisted as `STRING` (never ordinal).

The monolith had the backfill shape already (`TMDBSyncService`, `TMDBScheduledTasks`, `SyncStatus`); we rebuilt it clean inside the Catalog boundary and added the change-feed refresh it lacked.

## Interview lens
"For an external sync I make it resumable: persist a cursor per work stream, commit each page's data *and* its cursor in one transaction, and bound how much each scheduled run does. A crash resumes from the last committed page instead of restarting — and because my upserts key on the external id, re-running is idempotent, so retries and refreshes can't duplicate data. The one trap is Spring's self-invocation: the per-transaction unit has to sit on a separate bean or the proxy never applies it. But backfill isn't freshness — once it's done, the replica is frozen. So I add an incremental refresh on the upstream's change feed: pull the ids that changed in a window, intersect with what I actually store, re-hydrate only those, and advance a date cursor. It's at-least-once, which is fine because the upsert is idempotent; the real constraint is the feed's bounded history, so I clamp the lookback and accept that a longer outage needs a full re-walk to fully close the gap."
