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

## How we use it here (Catalog data load)
- `TmdbScheduledTasks` — the `@Scheduled(cron = "${tmdb.cron}")` trigger, guarded by `@ConditionalOnProperty("tmdb.enabled")` so it doesn't exist (and never calls TMDB) in tests or when no token is configured.
- `TmdbSyncService.syncAll()` — refreshes genres, then walks each `SyncType` up to `maxPagesPerRun` pages, isolating per-type failures.
- `CatalogUpserter` — the `@Transactional` per-page writer (movies + cursor in one commit).
- `TmdbApiClient` — the thin RestClient wrapper that talks to TMDB and maps wire DTOs → entities; the only thing tests mock, so the sync is unit-testable with no network. See [spring-web-annotations.md](spring-web-annotations.md) (`RestClient`).
- `SyncStatus` / `sync_status` — the bookkeeping entity + Liquibase changeset 002. Enums persisted as `STRING` (never ordinal).

The monolith had this shape already (`TMDBSyncService`, `TMDBScheduledTasks`, `SyncStatus`); we rebuilt it clean inside the Catalog boundary.

## Interview lens
"For an external sync I make it resumable: persist a cursor per work stream, commit each page's data *and* its cursor in one transaction, and bound how much each scheduled run does. A crash resumes from the last committed page instead of restarting — and because my upserts key on the external id, re-running is idempotent, so retries and refreshes can't duplicate data. The one trap is Spring's self-invocation: the per-transaction unit has to sit on a separate bean or the proxy never applies it."
