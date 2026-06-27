package com.gr74.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import com.gr74.catalog.config.JpaAuditingConfig;
import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;

/**
 * Persistence slice for {@link MovieRepository} on in-memory H2 (Liquibase off, Hibernate builds the
 * test schema). Proves two things the live endpoint depends on: the assigned-id + {@code @ManyToMany}
 * mapping round-trips, and {@link MovieRepository#findById(Long)} eager-fetches {@code genres} via its
 * {@code @EntityGraph} so the DTO mapper can read them after the transaction (we run
 * {@code open-in-view: false} in production).
 *
 * <p>{@link JpaAuditingConfig} is imported so {@code @CreatedDate} populates the NOT NULL
 * {@code created_date} — {@code @DataJpaTest} doesn't load it automatically.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class MovieRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private MovieRepository movieRepository;

    @Test
    void persistsAndFetchesMovieWithGenres() {
        Genre drama = entityManager.persist(new Genre(18L, "Drama"));
        Movie movie = new Movie(550L, "Fight Club");
        movie.addGenre(drama);
        entityManager.persist(movie);
        entityManager.flush();
        entityManager.clear(); // force a fresh load so the @EntityGraph fetch is actually exercised

        Optional<Movie> found = movieRepository.findById(550L);

        assertThat(found).isPresent();
        Movie loaded = found.get();
        assertThat(loaded.getTitle()).isEqualTo("Fight Club");
        assertThat(loaded.getCreatedDate()).isNotNull(); // auditing populated it
        assertThat(loaded.getGenres())
                .extracting(Genre::getName)
                .containsExactly("Drama");
    }

    @Test
    void returnsEmptyForUnknownId() {
        assertThat(movieRepository.findById(404L)).isEmpty();
    }
}
