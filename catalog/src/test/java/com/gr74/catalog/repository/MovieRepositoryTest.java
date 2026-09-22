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

/** Persistence slice for {@link MovieRepository} on H2. */
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
