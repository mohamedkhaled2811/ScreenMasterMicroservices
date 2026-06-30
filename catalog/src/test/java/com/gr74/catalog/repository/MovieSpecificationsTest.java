package com.gr74.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import com.gr74.catalog.config.JpaAuditingConfig;
import com.gr74.catalog.controller.dto.MovieFilter;
import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.repository.spec.MovieSpecifications;

/**
 * Persistence slice proving the dynamic filter on in-memory H2. Each test builds a {@link MovieFilter},
 * turns it into a composed {@link Specification} via {@link MovieSpecifications}, and asserts the right
 * movies come back through {@link MovieRepository#findMoviePage}. This is the read-side counterpart to
 * the monolith's {@code MovieSpecification} behaviour, verified end-to-end against a real query.
 *
 * <p>{@link JpaAuditingConfig} is imported so {@code @CreatedDate} populates the NOT NULL
 * {@code created_date} on persisted movies — {@code @DataJpaTest} doesn't load it automatically.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class MovieSpecificationsTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private MovieRepository movies;

    private Genre action;
    private Genre drama;

    @BeforeEach
    void seed() {
        action = em.persist(new Genre(28L, "Action"));
        drama = em.persist(new Genre(18L, "Drama"));

        persistMovie(603L, "The Matrix", "en", LocalDate.of(1999, 3, 31), "8.2", false, action);
        persistMovie(550L, "Fight Club", "en", LocalDate.of(1999, 10, 15), "8.4", false, drama);
        persistMovie(155L, "The Dark Knight", "en", LocalDate.of(2008, 7, 16), "8.5", false, action, drama);
        persistMovie(13L, "Amélie", "fr", LocalDate.of(2001, 4, 25), "7.9", false, drama);
        em.flush();
        em.clear();
    }

    @Test
    void titleContainsIsCaseInsensitiveSubstring() {
        Page<Movie> page = search(new MovieFilter("matrix", null, null, null, null, null, null));
        assertThat(page.getContent()).extracting(Movie::getTitle).containsExactly("The Matrix");
    }

    @Test
    void filterByGenreId() {
        Page<Movie> page = search(new MovieFilter(null, 28L, null, null, null, null, null));
        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactlyInAnyOrder("The Matrix", "The Dark Knight");
    }

    @Test
    void filterByLanguage() {
        Page<Movie> page = search(new MovieFilter(null, null, "fr", null, null, null, null));
        assertThat(page.getContent()).extracting(Movie::getTitle).containsExactly("Amélie");
    }

    @Test
    void filterByReleaseYearRange() {
        Page<Movie> page = search(new MovieFilter(null, null, null, 2000, 2010, null, null));
        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactlyInAnyOrder("The Dark Knight", "Amélie");
    }

    @Test
    void filterByMinRating() {
        Page<Movie> page = search(new MovieFilter(null, null, null, null, null, new BigDecimal("8.4"), null));
        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactlyInAnyOrder("Fight Club", "The Dark Knight");
    }

    @Test
    void predicatesComposeWithAnd() {
        // genre=Action AND year>=2000 AND rating>=8.0  -> only The Dark Knight
        Page<Movie> page = search(new MovieFilter(null, 28L, "en", 2000, null, new BigDecimal("8.0"), null));
        assertThat(page.getContent()).extracting(Movie::getTitle).containsExactly("The Dark Knight");
    }

    @Test
    void emptyFilterReturnsAllPaged() {
        Page<Movie> page = search(new MovieFilter(null, null, null, null, null, null, null));
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).hasSize(4);
    }

    @Test
    void genresAreFetchedWithinTheTransaction() {
        // open-in-view is off; reading genres after findMoviePage proves the @EntityGraph fetch worked.
        Page<Movie> page = search(new MovieFilter(null, 18L, null, null, null, null, null));
        assertThat(page.getContent())
                .allSatisfy(m -> assertThat(m.getGenres()).isNotEmpty());
    }

    @Test
    void sortOrderIsPreserved() {
        Page<Movie> page = movies.findMoviePage(
                MovieSpecifications.from(new MovieFilter(null, null, null, null, null, null, null)),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "voteAverage")));
        assertThat(page.getContent()).extracting(Movie::getTitle)
                .containsExactly("The Dark Knight", "Fight Club", "The Matrix", "Amélie");
    }

    private Page<Movie> search(MovieFilter filter) {
        return movies.findMoviePage(MovieSpecifications.from(filter), PageRequest.of(0, 10));
    }

    private void persistMovie(long id, String title, String lang, LocalDate release,
                              String rating, boolean adult, Genre... genres) {
        Movie movie = new Movie(id, title);
        for (Genre g : genres) {
            movie.addGenre(g);
        }
        // The (id, title) ctor sets only the key columns; the filterable detail fields flow through
        // the same applyDetails() path the TMDB sync uses, so the test exercises the real mutation API.
        movie.applyDetails(b -> b
                .originalLanguage(lang)
                .releaseDate(release)
                .voteAverage(new BigDecimal(rating))
                .adult(adult));
        em.persist(movie);
    }
}
