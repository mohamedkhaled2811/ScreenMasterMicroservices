package com.gr74.catalog.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gr74.catalog.exception.CatalogErrorCode;
import com.gr74.catalog.exception.TmdbSyncException;
import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.sync.dto.TmdbMovieDetails;

/** Unit test for {@link TmdbApiClient} with mocked HTTP (no network). */
class TmdbApiClientTest {

    private static final String BASE = "https://api.themoviedb.org/3";

    private MockRestServiceServer server;
    private TmdbApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE)
                .defaultHeader("Authorization", "Bearer test-token");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TmdbApiClient(builder.build());
    }

    @Test
    void movieDetailsDeserializesAndSendsBearer() {
        server.expect(requestTo(BASE + "/movie/603"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(MATRIX_JSON, MediaType.APPLICATION_JSON));

        TmdbMovieDetails details = client.movieDetails(603L);

        assertThat(details.id()).isEqualTo(603L);
        assertThat(details.title()).isEqualTo("The Matrix");
        assertThat(details.originalLanguage()).isEqualTo("en");
        assertThat(details.runtime()).isEqualTo(136);
        assertThat(details.voteAverage()).isEqualByComparingTo("8.2");
        assertThat(details.genres()).extracting("name").containsExactly("Action", "Science Fiction");
        server.verify();
    }

    @Test
    void mapsDetailsToMovieEntity() {
        TmdbMovieDetails details = new TmdbMovieDetails(
                603L, "The Matrix", "The Matrix", "A hacker learns...", "Welcome to the Real World.",
                "1999-03-31", 136, "Released", "en", new BigDecimal("55.3"),
                new BigDecimal("8.2"), 24000, "/poster.jpg", "/backdrop.jpg", false, null);
        Genre action = new Genre(28L, "Action");

        Movie movie = client.toMovie(details, Set.of(action));

        assertThat(movie.getId()).isEqualTo(603L);
        assertThat(movie.getTitle()).isEqualTo("The Matrix");
        assertThat(movie.getReleaseDate()).isEqualTo(LocalDate.of(1999, 3, 31));
        assertThat(movie.getRuntime()).isEqualTo(136);
        assertThat(movie.getStatus()).isEqualTo("Released");
        assertThat(movie.getGenres()).extracting(Genre::getName).containsExactly("Action");
    }

    @Test
    void blankReleaseDateBecomesNull() {
        TmdbMovieDetails details = new TmdbMovieDetails(
                1L, "Unreleased", null, null, null, "", null, "Post Production", "en",
                null, null, null, null, null, false, null);

        Movie movie = client.toMovie(details, Set.of());

        assertThat(movie.getReleaseDate()).isNull();
    }

    @Test
    void listPageDeserializesAndExtractsIds() {
        server.expect(requestTo(BASE + "/movie/popular?page=1"))
                .andRespond(withSuccess(POPULAR_PAGE_JSON, MediaType.APPLICATION_JSON));

        var page = client.listPage(SyncType.POPULAR, 1);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.totalPages()).isEqualTo(500);
        assertThat(page.movieIds()).containsExactly(603L, 550L);
        server.verify();
    }

    @Test
    void changedMovieIdsSendsDateWindowAndExtractsIds() {
        server.expect(requestTo(BASE + "/movie/changes?start_date=2026-06-29&end_date=2026-06-30&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(CHANGES_PAGE_JSON, MediaType.APPLICATION_JSON));

        var page = client.changedMovieIds(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 6, 30), 1);

        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.movieIds()).containsExactly(603L, 550L);
        server.verify();
    }

    @Test
    void changesUpstreamErrorBecomesCodedSyncException() {
        LocalDate day = LocalDate.of(2026, 6, 30);
        server.expect(requestTo(BASE + "/movie/changes?start_date=2026-06-30&end_date=2026-06-30&page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.changedMovieIds(day, day, 1))
                .isInstanceOf(TmdbSyncException.class)
                .extracting("errorCode")
                .isEqualTo(CatalogErrorCode.CATALOG_TMDB_SYNC_ERROR);
    }

    @Test
    void upstreamErrorBecomesCodedSyncException() {
        server.expect(requestTo(BASE + "/movie/603")).andRespond(withServerError());

        assertThatThrownBy(() -> client.movieDetails(603L))
                .isInstanceOf(TmdbSyncException.class)
                .extracting("errorCode")
                .isEqualTo(CatalogErrorCode.CATALOG_TMDB_SYNC_ERROR);
    }

    private static final String MATRIX_JSON = """
            {
              "id": 603,
              "title": "The Matrix",
              "original_title": "The Matrix",
              "overview": "A hacker learns...",
              "tagline": "Welcome to the Real World.",
              "release_date": "1999-03-31",
              "runtime": 136,
              "status": "Released",
              "original_language": "en",
              "popularity": 55.3,
              "vote_average": 8.2,
              "vote_count": 24000,
              "poster_path": "/poster.jpg",
              "backdrop_path": "/backdrop.jpg",
              "adult": false,
              "budget": 63000000,
              "genres": [
                {"id": 28, "name": "Action"},
                {"id": 878, "name": "Science Fiction"}
              ]
            }
            """;

    private static final String POPULAR_PAGE_JSON = """
            {
              "page": 1,
              "total_pages": 500,
              "total_results": 10000,
              "results": [
                {"id": 603, "title": "The Matrix"},
                {"id": 550, "title": "Fight Club"}
              ]
            }
            """;

    private static final String CHANGES_PAGE_JSON = """
            {
              "page": 1,
              "total_pages": 3,
              "total_results": 6,
              "results": [
                {"id": 603, "adult": false},
                {"id": 550, "adult": false}
              ]
            }
            """;
}
