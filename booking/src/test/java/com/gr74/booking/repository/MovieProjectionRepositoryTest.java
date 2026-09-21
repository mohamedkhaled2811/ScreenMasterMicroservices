package com.gr74.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.gr74.booking.model.MovieProjection;

/**
 * Persistence slice for the {@link MovieProjection} read model on H2. Proves the two facts way B's write side
 * depends on: an assigned-PK {@code save} is an idempotent UPSERT (re-saving the same id updates the row,
 * never duplicates it), and {@link MovieProjectionRepository#findByIdIn} batch-loads a page's titles (the local
 * join). No {@code @Import(JpaAuditingConfig)} — {@code updatedAt} here is the upstream timestamp we store
 * verbatim, not an audited local write time.
 */
@DataJpaTest
class MovieProjectionRepositoryTest {

    @Autowired
    private MovieProjectionRepository repository;

    @Test
    void savingSameIdTwiceUpsertsInsteadOfDuplicating() {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-08-02T10:00:00Z");

        repository.saveAndFlush(new MovieProjection(603L, "The Matrix", "/matrix.jpg", t1));
        repository.saveAndFlush(new MovieProjection(603L, "The Matrix Resurrections", "/resurrections.jpg", t2));

        assertThat(repository.count()).isEqualTo(1);
        MovieProjection row = repository.findById(603L).orElseThrow();
        assertThat(row.getTitle()).isEqualTo("The Matrix Resurrections");
        assertThat(row.getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    void applyUpdatesTitleAndTimestampInPlace() {
        Instant t1 = Instant.parse("2026-08-01T10:00:00Z");
        MovieProjection row = repository.saveAndFlush(new MovieProjection(550L, "Fight Club", "/fightclub.jpg", t1));

        Instant t2 = Instant.parse("2026-08-05T10:00:00Z");
        row.apply("Fight Club (Director's Cut)", "/fightclub-dc.jpg", t2);
        repository.saveAndFlush(row);

        MovieProjection reloaded = repository.findById(550L).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Fight Club (Director's Cut)");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(t2);
    }

    @Test
    void findByIdInLoadsOnlyRequestedIds() {
        repository.saveAndFlush(new MovieProjection(1L, "A", null, null));
        repository.saveAndFlush(new MovieProjection(2L, "B", null, null));
        repository.saveAndFlush(new MovieProjection(3L, "C", null, null));

        List<MovieProjection> found = repository.findByIdIn(List.of(1L, 3L, 999L));

        assertThat(found).extracting(MovieProjection::getId).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void updatedAtMayBeNullForLazyBackfilledRow() {
        repository.saveAndFlush(new MovieProjection(42L, "Backfilled by Catalog fetch", null, null));

        MovieProjection row = repository.findById(42L).orElseThrow();
        assertThat(row.getUpdatedAt()).isNull();
        assertThat(row.getTitle()).isEqualTo("Backfilled by Catalog fetch");
    }
}
