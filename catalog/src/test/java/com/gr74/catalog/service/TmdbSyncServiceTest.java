package com.gr74.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.catalog.config.JpaAuditingConfig;
import com.gr74.catalog.config.TmdbProps;
import com.gr74.catalog.model.SyncState;
import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.repository.MovieRepository;
import com.gr74.catalog.repository.SyncStatusRepository;
import com.gr74.catalog.sync.TmdbApiClient;
import com.gr74.catalog.sync.dto.TmdbGenre;
import com.gr74.catalog.sync.dto.TmdbListPage;
import com.gr74.catalog.sync.dto.TmdbMovieDetails;

/** Sync slice: real services + repositories on H2, mocked TMDB client. */
@DataJpaTest
@Import({TmdbSyncService.class, CatalogUpserter.class, JpaAuditingConfig.class,
        TmdbSyncServiceTest.TestConfig.class})
class TmdbSyncServiceTest {

    static class TestConfig {
        @org.springframework.context.annotation.Bean
        TmdbProps tmdbProps() {
            // base/image/cron defaulted by the compact ctor; 2 pages per tick for the bound test.
            // changesEnabled=false so the backfill tests don't trip the incremental refresh; the
            // changes path has its own test that flips it on via a dedicated props instance.
            return new TmdbProps("test-token", null, null, true, null, 2, false, 14);
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

    @BeforeEach
    void stubGenresAndDetails() {
        given(tmdb.genres()).willReturn(List.of(new TmdbGenre(28L, "Action")));
        // Every requested movie id hydrates to a minimal valid movie linked to the Action genre.
        lenient().when(tmdb.movieDetails(anyLong())).thenAnswer(inv -> details(inv.getArgument(0)));
        // The real mapper is used (it's a non-mocked method) — re-stub toMovie to delegate to a real client.
        lenient().when(tmdb.toMovie(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenCallRealMethod();
    }

    @Test
    void syncingTwiceIsIdempotent() {
        // POPULAR has a single page of two movies; NOW_PLAYING/TOP_RATED have an empty single page.
        given(tmdb.listPage(SyncType.POPULAR, 1)).willReturn(page(1, 1, 603L, 550L));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(page(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(page(1, 1));

        syncService.syncAll();
        long afterFirst = movieRepository.count();
        syncService.syncAll(); // re-sync the same pages
        long afterSecond = movieRepository.count();

        assertThat(afterFirst).isEqualTo(2);
        assertThat(afterSecond).isEqualTo(2); // save() on assigned ids UPDATED, did not duplicate
    }

    @Test
    void failureMidWalkRecordsResumePoint() {
        // POPULAR: page 1 ok, page 2 throws. maxPagesPerRun=2, totalPages=5 so the walk attempts both.
        given(tmdb.listPage(SyncType.POPULAR, 1)).willReturn(page(1, 5, 603L));
        given(tmdb.listPage(SyncType.POPULAR, 2)).willThrow(new RuntimeException("TMDB 503"));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(page(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(page(1, 1));

        syncService.syncAll();

        SyncStatus popular = syncStatusRepository.findBySyncType(SyncType.POPULAR).orElseThrow();
        assertThat(popular.getState()).isEqualTo(SyncState.FAILED);
        assertThat(popular.getErrorMessage()).contains("page 2");
        assertThat(popular.getLastPage()).isEqualTo(1); // page 1 committed; resume from page 2
        assertThat(movieRepository.findById(603L)).isPresent();
    }

    @Test
    void nextTickResumesFromLastPage() {
        // First tick fails on page 2; second tick should fetch page 2 onward, not re-fetch page 1.
        // Use do*/when so re-stubbing a method that currently throws doesn't trigger the throw during
        // stub setup (the given(mock.call()) form would invoke the still-throwing stub).
        org.mockito.Mockito.doReturn(page(1, 2, 603L)).when(tmdb).listPage(SyncType.POPULAR, 1);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(tmdb).listPage(SyncType.POPULAR, 2);
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(page(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(page(1, 1));
        syncService.syncAll();
        assertThat(syncStatusRepository.findBySyncType(SyncType.POPULAR).orElseThrow().getLastPage()).isEqualTo(1);

        // Heal page 2 and run again — it should complete (totalPages=2) without re-fetching page 1.
        org.mockito.Mockito.doReturn(page(2, 2, 550L)).when(tmdb).listPage(SyncType.POPULAR, 2);
        syncService.syncAll();

        SyncStatus popular = syncStatusRepository.findBySyncType(SyncType.POPULAR).orElseThrow();
        assertThat(popular.getState()).isEqualTo(SyncState.COMPLETED);
        assertThat(popular.getLastPage()).isEqualTo(2);
        assertThat(popular.isComplete()).isTrue();
        assertThat(movieRepository.count()).isEqualTo(2);
        // page 1 fetched exactly once across both ticks (resume did not redo it)
        org.mockito.Mockito.verify(tmdb, org.mockito.Mockito.times(1)).listPage(SyncType.POPULAR, 1);
    }

    @Test
    void completedTypeIsSkippedOnNextTick() {
        given(tmdb.listPage(SyncType.POPULAR, 1)).willReturn(page(1, 1, 603L));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(page(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(page(1, 1));
        syncService.syncAll();
        syncService.syncAll(); // POPULAR is COMPLETE now; page 1 must not be fetched a second time

        org.mockito.Mockito.verify(tmdb, org.mockito.Mockito.times(1)).listPage(SyncType.POPULAR, 1);
    }

    private static TmdbListPage page(int page, int totalPages, Long... ids) {
        List<TmdbListPage.Result> results = java.util.Arrays.stream(ids)
                .map(TmdbListPage.Result::new).toList();
        return new TmdbListPage(page, totalPages, ids.length, results);
    }

    private static TmdbMovieDetails details(long id) {
        return new TmdbMovieDetails(id, "Movie " + id, "Movie " + id, "overview", null,
                "2020-01-01", 120, "Released", "en", new BigDecimal("50.0"),
                new BigDecimal("7.5"), 1000, "/p.jpg", "/b.jpg", false, List.of(new TmdbGenre(28L, "Action")));
    }
}
