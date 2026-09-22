package com.gr74.catalog.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.gr74.catalog.config.TmdbProps;
import com.gr74.catalog.model.SyncState;
import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.repository.MovieRepository;
import com.gr74.catalog.repository.SyncStatusRepository;
import com.gr74.catalog.sync.TmdbApiClient;
import com.gr74.catalog.sync.dto.TmdbChangesPage;
import com.gr74.catalog.sync.dto.TmdbListPage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the resumable TMDB sync.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbSyncService {

    private final TmdbApiClient tmdb;
    private final CatalogUpserter upserter;
    private final SyncStatusRepository syncStatusRepository;
    private final MovieRepository movieRepository;
    private final TmdbProps props;
    /** UTC clock for the change-feed window; injectable so tests can pin "today". */
    private final Clock clock = Clock.systemUTC();

    /**
     * One full tick: refresh genres, advance each backfill list, then run the incremental refresh if due.
     */
    public void syncAll() {
        log.info("TMDB sync tick starting (maxPagesPerRun={})", props.maxPagesPerRun());
        upserter.upsertGenres(tmdb.genres());
        for (SyncType type : SyncType.values()) {
            if (!type.isBackfillList()) {
                continue; // CHANGES is driven by syncChanges(), not the page-walk backfill.
            }
            try {
                syncType(type);
            } catch (RuntimeException e) {
                // Isolate failures per type: one list erroring must not stop the others this tick.
                log.error("Sync for {} failed this tick: {}", type, e.getMessage());
            }
        }
        if (props.changesEnabled() && backfillComplete()) {
            try {
                syncChanges();
            } catch (RuntimeException e) {
                log.error("Incremental change refresh failed this tick: {}", e.getMessage());
            }
        }
        log.info("TMDB sync tick finished");
    }

    /** True once every backfill list is {@code COMPLETED}. */
    private boolean backfillComplete() {
        for (SyncType type : SyncType.values()) {
            if (!type.isBackfillList()) {
                continue;
            }
            boolean complete = syncStatusRepository.findBySyncType(type)
                    .map(s -> s.getState() == SyncState.COMPLETED)
                    .orElse(false);
            if (!complete) {
                return false;
            }
        }
        return true;
    }

    /**
     * Advance one movie list, committing each page via {@link CatalogUpserter}.
     */
    void syncType(SyncType type) {
        SyncStatus status = syncStatusRepository.findBySyncType(type)
                .orElseGet(() -> syncStatusRepository.save(new SyncStatus(type)));

        if (status.isComplete()) {
            log.debug("{} already complete ({} pages) — skipping", type, status.getTotalPages());
            return;
        }

        status.markRunning();
        syncStatusRepository.save(status);

        int startPage = status.getLastPage() + 1;
        int lastAttempted = status.getLastPage();
        try {
            for (int i = 0; i < props.maxPagesPerRun(); i++) {
                int page = startPage + i;
                if (status.getTotalPages() > 0 && page > status.getTotalPages()) {
                    break; // walked past the end of the list
                }
                lastAttempted = page;
                TmdbListPage listPage = tmdb.listPage(type, page);
                upserter.upsertPage(status, listPage.movieIds(), page, listPage.totalPages());
                // Reflect the just-committed cursor on our in-memory copy for the loop bounds.
                status.recordPageSynced(page, listPage.totalPages());
                if (status.isComplete()) {
                    break;
                }
            }
            finalizeRun(status);
        } catch (RuntimeException e) {
            log.error("{} sync failed at page {}: {}", type, lastAttempted, e.getMessage());
            SyncStatus managed = syncStatusRepository.findBySyncType(type).orElse(status);
            managed.markFailed(type + " failed at page " + lastAttempted + ": " + e.getMessage());
            syncStatusRepository.save(managed);
            throw e;
        }
    }

    /** Mark the row COMPLETED if fully synced; otherwise leave it resumable. */
    private void finalizeRun(SyncStatus status) {
        SyncStatus managed = syncStatusRepository.findBySyncType(status.getSyncType()).orElse(status);
        if (managed.isComplete()) {
            managed.markCompleted();
            log.info("{} sync complete: {} pages", managed.getSyncType(), managed.getTotalPages());
        } else {
            // More pages remain for a future tick; clear RUNNING back to a neutral resumable state by
            // recording the last good page (COMPLETED only means "fully done").
            log.info("{} sync paused at page {}/{} — resumes next tick",
                    managed.getSyncType(), managed.getLastPage(), managed.getTotalPages());
        }
        syncStatusRepository.save(managed);
    }

    /**
     * Incremental refresh: re-hydrate changed movies one UTC day at a time.
     */
    void syncChanges() {
        SyncStatus status = syncStatusRepository.findBySyncType(SyncType.CHANGES)
                .orElseGet(() -> syncStatusRepository.save(new SyncStatus(SyncType.CHANGES)));

        LocalDate today = LocalDate.now(clock);
        LocalDate cursor = status.getLastChangesSyncedDate();
        // First run (no cursor): start at the lookback floor. Otherwise resume the day after the cursor.
        LocalDate firstDay = (cursor == null)
                ? today.minusDays(props.changesLookbackDays())
                : cursor.plusDays(1);

        if (firstDay.isAfter(today)) {
            log.debug("Changes already fresh through {} — nothing to refresh", cursor);
            return;
        }

        log.info("Refreshing changed movies for {} .. {}", firstDay, today);
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            refreshDay(status, day);
        }
    }

    /** Refresh one UTC day's worth of changes. */
    private void refreshDay(SyncStatus status, LocalDate day) {
        List<Long> changedStored = new ArrayList<>();
        int totalPages = 1;
        int lastWalked = 0;
        for (int page = 1; page <= totalPages && page <= props.maxPagesPerRun(); page++) {
            TmdbChangesPage changes = tmdb.changedMovieIds(day, day, page);
            totalPages = changes.totalPages();
            lastWalked = page;
            List<Long> ids = changes.movieIds();
            if (!ids.isEmpty()) {
                // Keep only movies we own — never re-fetch TMDB's full change firehose.
                changedStored.addAll(movieRepository.findExistingIds(ids));
            }
        }
        // Hold the cursor on the prior day if the page cap cut this day short, so the next tick
        // re-walks it in full; otherwise advance to the day we just finished.
        LocalDate cursorThrough = lastWalked >= totalPages ? day : day.minusDays(1);
        upserter.refreshMovies(status, changedStored, cursorThrough);
    }
}
