package com.gr74.catalog.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gr74.catalog.service.TmdbSyncService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The cron trigger for the TMDB sync. Thin on purpose: it owns <em>when</em> the sync runs and nothing
 * else — the resumable walk lives in {@link TmdbSyncService}.
 *
 * <p>{@code @ConditionalOnProperty(tmdb.enabled=true)} is the enablement guard: when sync is disabled
 * (the test profile, or any environment without a {@code TMDB_API_KEY}) this bean is never created, so
 * {@code @Scheduled} registers nothing and no background call to TMDB ever fires. That's what keeps the
 * {@code contextLoads} smoke test hermetic without stubbing a scheduler. The cron expression is bound
 * from {@code tmdb.cron} (hourly by default). See {@code docs/concepts/scheduled-resumable-sync.md}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "tmdb.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TmdbScheduledTasks {

    private final TmdbSyncService syncService;

    /**
     * Fire the sync on the configured cron. {@code initialDelayString} would let us also kick one off
     * shortly after boot, but we keep it cron-only so the cadence is governed by a single property and
     * a freshly-started container doesn't hammer TMDB before it's ready. Any error escaping a tick is
     * logged here so a single bad run never kills the scheduler thread.
     */
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
