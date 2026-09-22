package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.client.CatalogClient.MovieProjectionData;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.model.MovieProjection;
import com.gr74.booking.repository.MovieProjectionRepository;

/**
 * The read side of way B, on H2 with {@link CatalogClient} mocked (the lazy-backfill boundary). Proves the
 * three behaviours that make way B both useful and safe:
 * <ol>
 *   <li>a cached title is served by the local join with <b>no Catalog call</b> (the whole point — this is
 *       what survives a Catalog outage);</li>
 *   <li>a miss is lazily backfilled from Catalog and <b>cached</b> (so the next read is local);</li>
 *   <li>a miss <em>while Catalog is unavailable</em> serves a null title and <b>writes nothing</b> — the
 *       cache-poisoning guard, so the miss retries on the next read instead of a placeholder hiding it.</li>
 * </ol>
 */
@DataJpaTest
@Import({MovieReadModel.class, MovieBackfiller.class})
class MovieReadModelTest {

    @Autowired
    private MovieReadModel readModel;

    @Autowired
    private MovieProjectionRepository repository;

    @MockitoBean
    private CatalogClient catalogClient;

    @Test
    void cachedTitleServedLocallyWithoutCallingCatalog() {
        repository.saveAndFlush(new MovieProjection(603L, "The Matrix", "/matrix.jpg", null));

        Map<Long, String> titles = readModel.titlesByIds(Set.of(603L));

        assertThat(titles).containsEntry(603L, "The Matrix");
        verify(catalogClient, never()).projectionById(603L); // local hit — no network
    }

    @Test
    void missIsLazilyBackfilledFromCatalogAndCached() {
        given(catalogClient.projectionById(550L)).willReturn(Optional.of(new MovieProjectionData("Fight Club", "/fightclub.jpg")));

        Map<Long, String> titles = readModel.titlesByIds(Set.of(550L));

        assertThat(titles).containsEntry(550L, "Fight Club");
        // Cached for next time (updatedAt null: from a fetch, not an event).
        MovieProjection cached = repository.findById(550L).orElseThrow();
        assertThat(cached.getTitle()).isEqualTo("Fight Club");
        // The poster is cached by the SAME backfill — one fetch fills both columns, so the ticket
        // email's artwork never costs a second Catalog call.
        assertThat(cached.getPosterPath()).isEqualTo("/fightclub.jpg");
        assertThat(cached.getUpdatedAt()).isNull();
    }

    @Test
    void missWhileCatalogDownServesNullAndDoesNotPoisonTheCache() {
        given(catalogClient.projectionById(777L)).willThrow(new CatalogUnavailableException(777L, new RuntimeException("down")));

        Map<Long, String> titles = readModel.titlesByIds(Set.of(777L));

        // No entry (caller renders null) AND crucially nothing was written — the miss will retry next read.
        assertThat(titles).doesNotContainKey(777L);
        assertThat(repository.findById(777L)).isEmpty();
    }

    @Test
    void missOn404DoesNotCacheButServesNull() {
        given(catalogClient.projectionById(999L)).willReturn(Optional.empty()); // Catalog says "no such movie"

        Map<Long, String> titles = readModel.titlesByIds(Set.of(999L));

        assertThat(titles).doesNotContainKey(999L);
        assertThat(repository.findById(999L)).isEmpty(); // nothing to cache for an unknown movie
    }

    @Test
    void mixOfCachedAndBackfilledIdsResolvesBoth() {
        repository.saveAndFlush(new MovieProjection(603L, "The Matrix", "/matrix.jpg", null));
        given(catalogClient.projectionById(550L)).willReturn(Optional.of(new MovieProjectionData("Fight Club", "/fightclub.jpg")));

        Map<Long, String> titles = readModel.titlesByIds(Set.of(603L, 550L));

        assertThat(titles).containsEntry(603L, "The Matrix").containsEntry(550L, "Fight Club");
        verify(catalogClient, never()).projectionById(603L); // cached one never hit Catalog
    }
}
