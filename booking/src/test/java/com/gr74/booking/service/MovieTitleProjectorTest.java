package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.gr74.booking.messaging.MovieUpsertedEvent;
import com.gr74.booking.model.MovieTitle;
import com.gr74.booking.repository.MovieTitleRepository;

/**
 * The consumer's core behaviour, tested by <b>direct invocation</b> of {@link MovieTitleProjector} on H2 —
 * no broker. This deliberately does <em>not</em> exercise the exchange/queue/binding/routing-key/converter
 * wiring (a typo'd routing key would pass every test here); that seam is covered once, manually, in the
 * 2.4 live demo (sync a change → watch {@code movie_titles} update via the RabbitMQ console + logs).
 *
 * <p>What it proves — the three things that are actually ours:
 * <ol>
 *   <li>redelivery is idempotent (same event twice → one row, unchanged the second time);</li>
 *   <li>the ordering guard drops a strictly-older event (a late/duplicate old message can't overwrite a
 *       newer cached title), and applies a strictly-newer one;</li>
 *   <li>a lazy-backfilled row (null {@code updatedAt} baseline) is overwritten by the first real event.</li>
 * </ol>
 */
@DataJpaTest
@Import(MovieTitleProjector.class)
class MovieTitleProjectorTest {

    @Autowired
    private MovieTitleProjector projector;

    @Autowired
    private MovieTitleRepository repository;

    private static final long MOVIE = 603L;
    private static final Instant T1 = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-08-02T10:00:00Z");

    @Test
    void firstEventInsertsTheTitle() {
        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "The Matrix", T1, "evt-1"));

        assertThat(applied).isTrue();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieTitle::getTitle, MovieTitle::getUpdatedAt)
                .containsExactly("The Matrix", T1);
    }

    @Test
    void redeliveringTheSameEventIsIdempotent() {
        MovieUpsertedEvent event = new MovieUpsertedEvent(MOVIE, "The Matrix", T1, "evt-1");

        projector.apply(event);
        boolean secondApplied = projector.apply(event); // redelivery: equal timestamp -> not strictly newer

        assertThat(secondApplied).isFalse();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieTitle::getTitle).isEqualTo("The Matrix");
    }

    @Test
    void newerEventUpdatesTheTitle() {
        projector.apply(new MovieUpsertedEvent(MOVIE, "Old Title", T1, "evt-1"));

        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "New Title", T2, "evt-2"));

        assertThat(applied).isTrue();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieTitle::getTitle, MovieTitle::getUpdatedAt)
                .containsExactly("New Title", T2);
    }

    @Test
    void olderEventIsDroppedByTheGuard() {
        projector.apply(new MovieUpsertedEvent(MOVIE, "New Title", T2, "evt-2")); // newer stored first

        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "Stale Title", T1, "evt-1"));

        assertThat(applied).isFalse();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieTitle::getTitle, MovieTitle::getUpdatedAt)
                .containsExactly("New Title", T2); // unchanged
    }

    @Test
    void firstRealEventOverwritesLazyBackfilledRowWithNullBaseline() {
        // A lazy-backfilled row: title cached from a Catalog fetch, no event timestamp yet.
        repository.saveAndFlush(new MovieTitle(MOVIE, "Backfilled Title", null));

        boolean applied = projector.apply(new MovieUpsertedEvent(MOVIE, "Event Title", T1, "evt-1"));

        assertThat(applied).isTrue();
        assertThat(repository.findById(MOVIE)).get()
                .extracting(MovieTitle::getTitle, MovieTitle::getUpdatedAt)
                .containsExactly("Event Title", T1);
    }
}
