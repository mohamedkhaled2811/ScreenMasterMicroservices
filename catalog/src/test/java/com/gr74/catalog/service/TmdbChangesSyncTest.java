package com.gr74.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.catalog.config.JpaAuditingConfig;
import com.gr74.catalog.config.TmdbProps;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.repository.MovieRepository;
import com.gr74.catalog.repository.SyncStatusRepository;
import com.gr74.catalog.sync.TmdbApiClient;
import com.gr74.catalog.sync.dto.TmdbChangesPage;
import com.gr74.catalog.sync.dto.TmdbGenre;
import com.gr74.catalog.sync.dto.TmdbListPage;
import com.gr74.catalog.sync.dto.TmdbMovieDetails;

/**
 * Integration slice for the <em>incremental</em> change-feed refresh — the freshness half of the sync.
 * The real {@link TmdbSyncService} + {@link CatalogUpserter} + repositories run against H2 with only
 * the network boundary ({@link TmdbApiClient}) mocked, so we prove against persisted rows that the
 * refresh (a) only runs once backfill is complete, (b) re-hydrates only the movies we already store,
 * and (c) advances the date cursor.
 *
 * <p>The service's clock is {@code systemUTC}, so rather than pin wall-clock time we seed the
 * {@code CHANGES} cursor to <em>yesterday</em> — that makes the refresh window exactly
 * {@code [today, today]} deterministically, whatever the real date is.
 */
@DataJpaTest
@Import({TmdbSyncService.class, CatalogUpserter.class, JpaAuditingConfig.class,
        TmdbChangesSyncTest.TestConfig.class})
class TmdbChangesSyncTest {

    static class TestConfig {
        @org.springframework.context.annotation.Bean
        TmdbProps tmdbProps() {
            // changesEnabled=true; small lookback; 5 pages/run cap.
            return new TmdbProps("test-token", null, null, true, null, 5, true, 1);
        }
    }

    @MockitoBean
    private TmdbApiClient tmdb;

    @Autowired
    private TmdbSyncService syncService;

    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void stubGenresAndMapper() {
        given(tmdb.genres()).willReturn(List.of(new TmdbGenre(28L, "Action")));
        lenient().when(tmdb.toMovie(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenCallRealMethod();
    }

    @Test
    void refreshDoesNotRunUntilBackfillComplete() {
        // POPULAR reports 99 total pages but the per-tick cap is 5, so it can never complete this tick
        // → backfill is not complete → the incremental refresh must not fire.
        given(tmdb.listPage(eq(SyncType.POPULAR), anyInt())).willAnswer(inv ->
                listPage(inv.getArgument(1), 99, 603L));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(listPage(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(listPage(1, 1));
        lenient().when(tmdb.movieDetails(anyLong())).thenAnswer(inv -> details(inv.getArgument(0), "7.5"));

        syncService.syncAll();

        verify(tmdb, never()).changedMovieIds(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void refreshReHydratesOnlyStoredChangedMovies() {
        // 1) Backfill one movie (603) via a single complete page, so backfill is COMPLETE.
        backfillSingleMovie();

        // Seed the cursor to yesterday → window is exactly [today, today].
        seedChangesCursor(TODAY.minusDays(1));

        // 2) TMDB reports two changed ids today: 603 (we store it) and 999 (we don't).
        given(tmdb.changedMovieIds(TODAY, TODAY, 1))
                .willReturn(changesPage(1, 1, 603L, 999L));
        // 603 now has an updated rating upstream.
        given(tmdb.movieDetails(603L)).willReturn(details(603L, "9.1"));

        syncService.syncAll();

        // 603 was re-hydrated to the new rating; 999 was filtered out (never fetched, never stored).
        Movie refreshed = movieRepository.findById(603L).orElseThrow();
        assertThat(refreshed.getVoteAverage()).isEqualByComparingTo(new BigDecimal("9.1"));
        assertThat(movieRepository.findById(999L)).isEmpty();
        verify(tmdb, never()).movieDetails(999L);

        // Cursor advanced to today.
        SyncStatus changes = syncStatusRepository.findBySyncType(SyncType.CHANGES).orElseThrow();
        assertThat(changes.getLastChangesSyncedDate()).isEqualTo(TODAY);
    }

    @Test
    void emptyChangeDayStillAdvancesCursor() {
        backfillSingleMovie();
        seedChangesCursor(TODAY.minusDays(1));
        given(tmdb.changedMovieIds(TODAY, TODAY, 1)).willReturn(changesPage(1, 1));

        syncService.syncAll();

        SyncStatus changes = syncStatusRepository.findBySyncType(SyncType.CHANGES).orElseThrow();
        assertThat(changes.getLastChangesSyncedDate()).isEqualTo(TODAY);
    }

    /**
     * Backfill a single movie so all three lists are COMPLETED (the gate for the refresh). Backfill
     * completing means this same tick also runs a refresh — so we pre-seed the cursor to today first,
     * making that first auto-refresh an empty no-op. Each test then re-seeds to yesterday to make the
     * window it asserts on deterministic.
     */
    private void backfillSingleMovie() {
        seedChangesCursor(TODAY);
        given(tmdb.listPage(SyncType.POPULAR, 1)).willReturn(listPage(1, 1, 603L));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(listPage(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(listPage(1, 1));
        given(tmdb.movieDetails(603L)).willReturn(details(603L, "7.5"));
        syncService.syncAll();
        assertThat(movieRepository.findById(603L)).isPresent();
    }

    private void seedChangesCursor(LocalDate through) {
        SyncStatus changes = syncStatusRepository.findBySyncType(SyncType.CHANGES)
                .orElseGet(() -> new SyncStatus(SyncType.CHANGES));
        changes.recordChangesSynced(through);
        syncStatusRepository.save(changes);
    }

    private static TmdbListPage listPage(int page, int totalPages, Long... ids) {
        List<TmdbListPage.Result> results = java.util.Arrays.stream(ids)
                .map(TmdbListPage.Result::new).toList();
        return new TmdbListPage(page, totalPages, ids.length, results);
    }

    private static TmdbChangesPage changesPage(int page, int totalPages, Long... ids) {
        List<TmdbChangesPage.Result> results = java.util.Arrays.stream(ids)
                .map(TmdbChangesPage.Result::new).toList();
        return new TmdbChangesPage(page, totalPages, ids.length, results);
    }

    private static TmdbMovieDetails details(long id, String voteAverage) {
        return new TmdbMovieDetails(id, "Movie " + id, "Movie " + id, "overview", null,
                "2020-01-01", 120, "Released", "en", new BigDecimal("50.0"),
                new BigDecimal(voteAverage), 1000, "/p.jpg", "/b.jpg", false,
                List.of(new TmdbGenre(28L, "Action")));
    }
}
