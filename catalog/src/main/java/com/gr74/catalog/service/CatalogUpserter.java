package com.gr74.catalog.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.catalog.event.MovieUpserted;
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
 * Transactional write side of the TMDB sync. Publishes {@link MovieUpserted} on refresh only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogUpserter {

    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;
    private final SyncStatusRepository syncStatusRepository;
    private final TmdbApiClient tmdb;
    private final ApplicationEventPublisher events;

    /** Upsert the whole genre vocabulary in one transaction (parents before any movie links them). */
    @Transactional
    public void upsertGenres(List<TmdbGenre> genres) {
        for (TmdbGenre g : genres) {
            genreRepository.save(new Genre(g.id(), g.name()));
        }
        log.info("Upserted {} genres", genres.size());
    }

    /**
     * Hydrate and upsert every movie on a TMDB list page, then advance the cursor.
     *
     * @return the number of movies upserted on this page
     */
    @Transactional
    public int upsertPage(SyncStatus status, List<Long> movieIds, int page, int totalPages) {
        int upserted = 0;
        for (Long movieId : movieIds) {
            // Skip a single unfetchable movie rather than failing the whole page.
            if (hydrateOne(movieId, "%s page %d".formatted(status.getSyncType(), page)) != null) {
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
     * Re-hydrate a batch of already-known movies and advance the refresh cursor.
     *
     * @param status   the {@link SyncType#CHANGES} bookkeeping row
     * @param movieIds the stored ids that changed on {@code through}
     * @param through  the UTC day this batch refreshed
     * @return the number of movies refreshed
     */
    @Transactional
    public int refreshMovies(SyncStatus status, List<Long> movieIds, LocalDate through) {
        int refreshed = 0;
        for (Long movieId : movieIds) {
            Movie saved = hydrateOne(movieId, "changes " + through);
            if (saved != null) {
                refreshed++;
                // Published AFTER_COMMIT by MovieEventPublisher; backfill publishes nothing.
                events.publishEvent(new MovieUpserted(
                        saved.getId(), saved.getTitle(), saved.getPosterPath(),
                        saved.getLastModifiedDate(), UUID.randomUUID().toString()));
            }
        }
        SyncStatus managed = syncStatusRepository.findById(status.getId()).orElse(status);
        managed.recordChangesSynced(through);
        syncStatusRepository.save(managed);
        log.info("Refreshed {} changed movie(s) for {}", refreshed, through);
        return refreshed;
    }

    /**
     * Fetch one movie's details and upsert it. Returns null for an unfetchable movie.
     */
    private Movie hydrateOne(Long movieId, String context) {
        try {
            TmdbMovieDetails details = tmdb.movieDetails(movieId);
            Set<Genre> resolved = resolveGenres(details);
            return movieRepository.saveAndFlush(tmdb.toMovie(details, resolved));
        } catch (RuntimeException e) {
            log.warn("Skipping movie {} on {}: {}", movieId, context, e.getMessage());
            return null;
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
