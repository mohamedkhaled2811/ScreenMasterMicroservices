package com.gr74.catalog.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.repository.GenreRepository;
import com.gr74.catalog.repository.MovieRepository;
import com.gr74.catalog.repository.SyncStatusRepository;
import com.gr74.catalog.sync.TmdbApiClient;
import com.gr74.catalog.sync.dto.TmdbGenre;
import com.gr74.catalog.sync.dto.TmdbMovieDetails;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The transactional write side of the TMDB sync — kept as a <em>separate</em> bean from
 * {@code TmdbSyncService} on purpose. The sync's per-page work must commit in its own transaction so
 * that a crash mid-walk leaves an advanced, consistent cursor (the next tick resumes cleanly). If the
 * {@code @Transactional} methods lived on the orchestrating service, the orchestrator calling its own
 * method would bypass Spring's proxy (self-invocation) and silently lose the transaction. Putting
 * them on this collaborator means every call crosses the proxy, so the boundaries are real.
 *
 * <p>All upserts are idempotent by construction: PKs are assigned TMDB ids, so {@code save()} on an
 * existing id is an UPDATE — re-syncing the same page updates rows, never duplicates them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogUpserter {

    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final TmdbApiClient tmdb;

    /** Upsert the whole genre vocabulary in one transaction (parents before any movie links them). */
    @Transactional
    public void upsertGenres(List<TmdbGenre> genres) {
        for (TmdbGenre g : genres) {
            genreRepository.save(new Genre(g.id(), g.name()));
        }
        log.info("Upserted {} genres", genres.size());
    }

    /**
     * Hydrate and upsert every movie on a TMDB list page, then advance the {@link SyncStatus} cursor —
     * all in one transaction. Each movie's full details are fetched via {@code GET /movie/{id}}; its
     * genres are resolved to already-persisted {@link Genre} rows (a movie referencing an unknown
     * genre id is skipped for that link, not invented). A single movie that fails to fetch is logged
     * and skipped so one bad id doesn't sink the page.
     *
     * @return the number of movies upserted on this page
     */
    @Transactional
    public int upsertPage(SyncStatus status, List<Long> movieIds, int page, int totalPages) {
        int upserted = 0;
        for (Long movieId : movieIds) {
            // Skip a single unfetchable movie rather than failing the whole page — the page cursor still
            // advances, and the missing movie is picked up on the next full pass.
            if (hydrateOne(movieId, "%s page %d".formatted(status.getSyncType(), page))) {
                upserted++;
            }
        }
        // Re-attach the status to this transaction and advance its cursor in the same commit as the rows.
        SyncStatus managed = syncStatusRepository.findById(status.getId()).orElse(status);
        managed.recordPageSynced(page, totalPages);
        syncStatusRepository.save(managed);
        log.info("Synced {} page {}/{}: {} movies", status.getSyncType(), page, totalPages, upserted);
        return upserted;
    }

    /**
     * Re-hydrate a batch of already-known movies from TMDB and advance the incremental-refresh cursor —
     * all in one transaction. This is the freshness counterpart to {@link #upsertPage}: the caller has
     * already filtered the change feed down to ids we store (see
     * {@code MovieRepository.findExistingIds}), so every fetch here updates an existing row (the upsert
     * is an UPDATE because the PK is the TMDB id). A single unfetchable movie is skipped so one bad id
     * doesn't sink the batch; the cursor still advances, since at-least-once + idempotent upserts make a
     * later re-pass of the same day harmless.
     *
     * @param status   the managed {@link SyncType#CHANGES} bookkeeping row
     * @param movieIds the stored ids that changed on {@code through}
     * @param through  the UTC day this batch refreshed — the cursor advances to it on commit
     * @return the number of movies refreshed
     */
    @Transactional
    public int refreshMovies(SyncStatus status, List<Long> movieIds, LocalDate through) {
        int refreshed = 0;
        for (Long movieId : movieIds) {
            if (hydrateOne(movieId, "changes " + through)) {
                refreshed++;
            }
        }
        SyncStatus managed = syncStatusRepository.findById(status.getId()).orElse(status);
        managed.recordChangesSynced(through);
        syncStatusRepository.save(managed);
        log.info("Refreshed {} changed movie(s) for {}", refreshed, through);
        return refreshed;
    }

    /**
     * Fetch one movie's full details and upsert it (an UPDATE when the id already exists, since the PK
     * is the TMDB id). Returns {@code true} on success; logs and returns {@code false} for a single
     * unfetchable movie so the caller can keep going. {@code context} is just for the warn log.
     */
    private boolean hydrateOne(Long movieId, String context) {
        try {
            TmdbMovieDetails details = tmdb.movieDetails(movieId);
            Set<Genre> resolved = resolveGenres(details);
            movieRepository.save(tmdb.toMovie(details, resolved));
            return true;
        } catch (RuntimeException e) {
            log.warn("Skipping movie {} on {}: {}", movieId, context, e.getMessage());
            return false;
        }
    }

    /** Resolve a movie's TMDB genres to persisted {@link Genre} entities; unknown ids are dropped. */
    private Set<Genre> resolveGenres(TmdbMovieDetails details) {
        Set<Genre> resolved = new LinkedHashSet<>();
        if (details.genres() == null) {
            return resolved;
        }
        for (TmdbGenre g : details.genres()) {
            genreRepository.findById(g.id()).ifPresent(resolved::add);
        }
        return resolved;
    }
}
