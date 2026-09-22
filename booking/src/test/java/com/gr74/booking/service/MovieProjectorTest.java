package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.gr74.booking.messaging.MovieUpsertedEvent;
import com.gr74.booking.model.MovieProjection;
import com.gr74.booking.repository.MovieProjectionRepository;

/**
 * Movie projection consumer behaviour by direct invocation on H2, without a broker.
 */
@DataJpaTest
@Import(MovieProjector.class)
class MovieProjectorTest {

    @Autowired
    private MovieProjector projector;

    @Autowired
    private MovieProjectionRepository repository;

    private static final long MOVIE = 603L;
    private static final Instant T1 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-02T10:00:00Z");

    @Test
    void firstEventInsertsTheTitle() {
        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "The Matrix", "/poster.jpg", T1, "evt-1"));

        assertThat(applied).isTrue();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieProjection::getTitle, MovieProjection::getUpdatedAt)
                .containsExactly("The Matrix", T1);
    }

    @Test
    void redeliveringTheSameEventIsIdempotent() {
        MovieUpsertedEvent event = new MovieUpsertedEvent(MOVIE, "The Matrix", "/poster.jpg", T1, "evt-1");

        projector.apply(event);
        boolean secondApplied = projector.apply(event); // redelivery: equal timestamp -> not strictly newer

        assertThat(secondApplied).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieProjection::getTitle).isEqualTo("The Matrix");
    }

    @Test
    void newerEventUpdatesTheTitle() {
        projector.apply(new MovieUpsertedEvent(MOVIE, "Old Title", "/poster.jpg", T1, "evt-1"));

        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "New Title", "/poster.jpg", T2, "evt-2"));

        assertThat(applied).isTrue();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieProjection::getTitle, MovieProjection::getUpdatedAt)
                .containsExactly("New Title", T2);
    }

    @Test
    void olderEventIsDroppedByTheGuard() {
        projector.apply(new MovieUpsertedEvent(MOVIE, "New Title", "/poster.jpg", T2, "evt-2")); // newer stored first

        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "Stale Title", "/poster.jpg", T1, "evt-1"));

        assertThat(applied).isFalse();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieProjection::getTitle, MovieProjection::getUpdatedAt)
                .containsExactly("New Title", T2); // unchanged
    }

    @Test
    void firstRealEventOverwritesLazyBackfilledRowWithNullBaseline() {
        // A lazy-backfilled row: title cached from a Catalog fetch, no event timestamp yet.
        repository.saveAndFlush(new MovieProjection(MOVIE, "Backfilled Title", null, null));

        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "Event Title", "/poster.jpg", T1, "evt-1"));

        assertThat(applied).isTrue();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieProjection::getTitle, MovieProjection::getUpdatedAt)
                .containsExactly("Event Title", T1);
    }
}
