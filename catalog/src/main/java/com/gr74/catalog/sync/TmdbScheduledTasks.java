package com.gr74.catalog.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gr74.catalog.service.TmdbSyncService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cron trigger for the TMDB sync. Owns scheduling only; the walk lives in {@link TmdbSyncService}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tmdb.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TmdbScheduledTasks {

    private final TmdbSyncService syncService;

    /** Fire the sync on the configured cron. Errors are logged so the scheduler keeps running. */
    @Scheduled(cron = "${tmdb.cron}")
    public void runScheduledSync() {
        log.info("Scheduled TMDB sync firing");
        try {
            syncService.syncAll();
        } catch (RuntimeException e) {
            log.error("Scheduled TMDB sync tick failed: {}", e.getMessage(), e);
        }
    }
}
