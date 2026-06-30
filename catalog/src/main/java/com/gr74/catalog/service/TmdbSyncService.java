package com.gr74.catalog.service;

import org.springframework.stereotype.Service;

import com.gr74.catalog.config.TmdbProps;
import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.repository.SyncStatusRepository;
import com.gr74.catalog.sync.TmdbApiClient;
import com.gr74.catalog.sync.dto.TmdbListPage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Orchestrates the resumable TMDB sync. This class is deliberately <em>not</em> {@code @Transactional}:
 * it drives the per-type walk and lets {@link CatalogUpserter} own the per-page transactions, so a
 * crash mid-walk leaves an advanced, committed cursor that the next tick resumes from. (See
 * {@code docs/concepts/scheduled-resumable-sync.md}.)
 *
 * <p>Per tick it first refreshes the genre vocabulary, then for each {@link SyncType} walks from
 * {@code lastPage + 1} up to {@link TmdbProps#maxPagesPerRun()} pages — our own back-pressure against
 * TMDB's rate limit. A type that is already {@code COMPLETED} (every page synced) is skipped, so once
 * the catalog is filled the hourly tick is nearly free; it only does work when there's more to fetch
 * or a prior run left a type {@code FAILED} to resume.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbSyncService {

    private final TmdbApiClient tmdb;
    private final CatalogUpserter upserter;
    private final SyncStatusRepository syncStatusRepository;
    private final TmdbProps props;

    /** One full tick: refresh genres, then advance each movie list by up to {@code maxPagesPerRun}. */
    public void syncAll() {
        log.info("TMDB sync tick starting (maxPagesPerRun={})", props.maxPagesPerRun());
        upserter.upsertGenres(tmdb.genres());
        for (SyncType type : SyncType.values()) {
            try {
                syncType(type);
            } catch (RuntimeException e) {
                // Isolate failures per type: one list erroring must not stop the others this tick.
                log.error("Sync for {} failed this tick: {}", type, e.getMessage());
            }
        }
        log.info("TMDB sync tick finished");
    }

    /**
     * Advance one movie list. Loads (or creates) its bookkeeping row, skips it if already complete,
     * otherwise walks the next {@code maxPagesPerRun} pages — committing each page in its own
     * transaction via {@link CatalogUpserter}. On any error the row is marked {@code FAILED} at the
     * last good page so the next tick resumes from exactly there.
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

    /** Mark the row COMPLETED if the whole list is synced; otherwise leave it ready to resume. */
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
}
