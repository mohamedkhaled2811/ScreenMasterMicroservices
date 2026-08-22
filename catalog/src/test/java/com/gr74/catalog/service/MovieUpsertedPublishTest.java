package com.gr74.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.catalog.config.JpaAuditingConfig;
import com.gr74.catalog.config.TmdbProps;
import com.gr74.catalog.event.MovieUpserted;
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
 * The publisher half of the CQRS read model (BUILD_PLAN 2.3, way B), asserted at the
 * <em>application-event</em> seam. We prove ADR 0001's core rule against the real
 * {@link TmdbSyncService} + {@link CatalogUpserter} on H2:
 * <ul>
 *   <li><b>backfill publishes nothing</b> — a bulk load must not flood the broker;</li>
 *   <li><b>the incremental refresh publishes one {@link MovieUpserted} per re-hydrated movie</b>, and</li>
 *   <li>the event's {@code updatedAt} is the movie row's real {@code @LastModifiedDate} (not null, not a
 *       publish-time stamp) — the value the consumer's ordering guard depends on.</li>
 * </ul>
 *
 * <p>Why assert at the application-event boundary rather than on {@code RabbitTemplate}: the broker send
 * is an {@code @TransactionalEventListener(AFTER_COMMIT)}, and this {@code @DataJpaTest} rolls each test
 * back, so {@code AFTER_COMMIT} never fires here. The event <em>raised inside the txn</em> is the
 * behaviour under test; the AFTER_COMMIT→broker hop is trivial glue verified live in the 2.4 demo.
 */
@DataJpaTest
@Import({TmdbSyncService.class, CatalogUpserter.class, JpaAuditingConfig.class,
        MovieUpsertedPublishTest.TestConfig.class, MovieUpsertedPublishTest.CapturingListener.class})
class MovieUpsertedPublishTest {

    static class TestConfig {
        @Bean
        TmdbProps tmdbProps() {
            return new TmdbProps("test-token", null, null, true, null, 5, true, 1);
        }
    }

    /** Captures MovieUpserted application events as CatalogUpserter raises them (inside the txn). */
    @Component
    static class CapturingListener {
        final List<MovieUpserted> events = new ArrayList<>();

        @EventListener
        void on(MovieUpserted e) {
            events.add(e);
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

    @Autowired
    private CapturingListener capturing;

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void stubGenresAndMapper() {
        // The Spring context (and this capturing bean) is shared across test methods — reset it so one
        // test's events never leak into another's assertion.
        capturing.events.clear();
        given(tmdb.genres()).willReturn(List.of(new TmdbGenre(28L, "Action")));
        lenient().when(tmdb.toMovie(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenCallRealMethod();
    }

    @Test
    void backfillPublishesNothing() {
        // A single complete page per list → backfill runs and COMPLETES, but must emit zero events.
        given(tmdb.listPage(SyncType.POPULAR, 1)).willReturn(listPage(1, 1, 603L, 550L));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(listPage(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(listPage(1, 1));
        given(tmdb.movieDetails(anyLong())).willAnswer(inv -> details(inv.getArgument(0), "7.5"));
        // Pre-seed the CHANGES cursor to today so the auto-refresh that follows a completed backfill is an
        // empty no-op — isolating this assertion to the backfill itself.
        seedChangesCursor(TODAY);

        syncService.syncAll();

        assertThat(movieRepository.findById(603L)).isPresent();
        assertThat(capturing.events).as("backfill must not publish MovieUpserted (ADR 0001)").isEmpty();
    }

    @Test
    void refreshPublishesOnePerReHydratedMovieWithRowTimestamp() {
        backfillSingleMovie(603L);       // 603 stored, backfill COMPLETE
        capturing.events.clear();        // drop the empty auto-refresh from backfill setup
        seedChangesCursor(TODAY.minusDays(1)); // window = [today, today]

        given(tmdb.changedMovieIds(TODAY, TODAY, 1)).willReturn(changesPage(1, 1, 603L, 999L));
        given(tmdb.movieDetails(603L)).willReturn(details(603L, "9.1"));

        syncService.syncAll();

        // Exactly one event: for 603 (we store it). 999 is filtered out before any fetch, so no event.
        assertThat(capturing.events).hasSize(1);
        MovieUpserted event = capturing.events.get(0);
        assertThat(event.id()).isEqualTo(603L);
        assertThat(event.title()).isEqualTo("Movie 603");
        assertThat(event.eventId()).isNotBlank();
        // updatedAt is the persisted row's @LastModifiedDate — present, and equal to what's in the DB.
        assertThat(event.updatedAt()).isNotNull();
        assertThat(event.updatedAt())
                .isEqualTo(movieRepository.findById(603L).orElseThrow().getLastModifiedDate());
    }

    private void backfillSingleMovie(long id) {
        seedChangesCursor(TODAY);
        given(tmdb.listPage(SyncType.POPULAR, 1)).willReturn(listPage(1, 1, id));
        given(tmdb.listPage(SyncType.TOP_RATED, 1)).willReturn(listPage(1, 1));
        given(tmdb.listPage(SyncType.NOW_PLAYING, 1)).willReturn(listPage(1, 1));
        given(tmdb.movieDetails(id)).willReturn(details(id, "7.5"));
        syncService.syncAll();
        assertThat(movieRepository.findById(id)).isPresent();
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
