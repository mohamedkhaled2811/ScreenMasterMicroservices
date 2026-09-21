package com.gr74.booking.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.exception.MovieNotInCatalogException;

/**
 * Unit test for {@link CatalogClient} with no network: a {@link MockRestServiceServer} bound to the
 * client's {@link RestClient} plays Catalog. This is the test that pins the 5C contract — the three
 * outcomes of validating a {@code movieId} across the service boundary must map to three distinct
 * results, because a bad id and a Catalog outage are different problems the saga later branches on.
 *
 * <p>The base URL here is a plain {@code http://catalog} stand-in for {@code lb://catalog} (the load
 * balancer isn't exercised in a unit test — resolution is a production concern proven live), so the
 * expected request path is the resolved {@code /movies/{id}} the client builds.
 */
class CatalogClientTest {

    private static final String BASE = "http://catalog";

    private MockRestServiceServer server;
    private CatalogClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CatalogClient(builder.build());
    }

    @Test
    void movieExistsWhenCatalogReturns200() {
        server.expect(requestTo(BASE + "/movies/603"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":603,\"title\":\"The Matrix\"}", MediaType.APPLICATION_JSON));

        assertThatCode(() -> client.verifyMovieExists(603L)).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void movie404BecomesMovieNotInCatalog() {
        server.expect(requestTo(BASE + "/movies/999999"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.verifyMovieExists(999_999L))
                .isInstanceOf(MovieNotInCatalogException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.BOOKING_MOVIE_NOT_FOUND);
        server.verify();
    }

    @Test
    void catalog5xxBecomesCatalogUnavailable() {
        server.expect(requestTo(BASE + "/movies/603"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.verifyMovieExists(603L))
                .isInstanceOf(CatalogUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.BOOKING_CATALOG_UNAVAILABLE);
        server.verify();
    }

    @Test
    void unexpected4xxBecomesCatalogUnavailable() {
        // A non-404 client error (e.g. Catalog rejects a malformed id as 400) isn't "no such movie" —
        // we can't trust it as a definitive answer, so it's an availability problem, not a bad id.
        server.expect(requestTo(BASE + "/movies/603"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.verifyMovieExists(603L))
                .isInstanceOf(CatalogUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.BOOKING_CATALOG_UNAVAILABLE);
        server.verify();
    }

    // ---- titlesByIds (the read-path composition helper) -----------------------------------------

    @Test
    void titlesByIdsBuildsMapFromOneBatchCall() {
        // One call for the whole set (the N+1 fix), hitting /movies/batch with the ids as query params.
        server.expect(ExpectedCount.once(), requestToUriTemplate(BASE + "/movies/batch?ids=603&ids=550"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("ids", "603", "550"))
                .andRespond(withSuccess(
                        "[{\"id\":603,\"title\":\"The Matrix\"},{\"id\":550,\"title\":\"Fight Club\"}]",
                        MediaType.APPLICATION_JSON));

        // A LinkedHashSet keeps the query-param order deterministic for the URI match above.
        Map<Long, String> titles = client.titlesByIds(new java.util.LinkedHashSet<>(java.util.List.of(603L, 550L)));

        assertThat(titles).containsEntry(603L, "The Matrix").containsEntry(550L, "Fight Club");
        server.verify();
    }

    @Test
    void titlesByIdsDegradesToEmptyMapWhenCatalogIsDown() {
        // The read path must NOT throw — a Catalog outage returns no titles so "my bookings" still answers.
        server.expect(requestTo(BASE + "/movies/batch?ids=603")).andRespond(withServerError());

        Map<Long, String> titles = client.titlesByIds(Set.of(603L));

        assertThat(titles).isEmpty();
        server.verify();
    }

    @Test
    void titlesByIdsShortCircuitsOnEmptyInputWithNoCall() {
        // No ids → no network call at all (server.verify() would fail if an unexpected request fired).
        assertThat(client.titlesByIds(Set.of())).isEmpty();
        server.verify();
    }

    // ---- projectionById (the way-B lazy-backfill cache-fill) -------------------------------------
    // Distinct from titlesByIds: it writes into a persistent cache, so it must tell 404 (safe to treat as
    // "unknown") apart from unavailable (must NOT be cached — would poison the cache).

    @Test
    void projectionByIdReturnsTitleAndPosterOn200() {
        server.expect(requestTo(BASE + "/movies/603"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":603,\"title\":\"The Matrix\",\"posterPath\":\"/matrix.jpg\"}",
                        MediaType.APPLICATION_JSON));

        // Both projected columns come back from ONE fetch — the poster is what the ticket email renders.
        assertThat(client.projectionById(603L)).get()
                .extracting(CatalogClient.MovieProjectionData::title, CatalogClient.MovieProjectionData::posterPath)
                .containsExactly("The Matrix", "/matrix.jpg");
        server.verify();
    }

    @Test
    void projectionByIdKeepsTitleWhenTheMovieHasNoPoster() {
        // TMDB genuinely lacks artwork for some titles. A null poster must NOT suppress the title —
        // the ticket renders without the image band rather than without the film's name.
        server.expect(requestTo(BASE + "/movies/604"))
                .andRespond(withSuccess("{\"id\":604,\"title\":\"Obscure Film\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.projectionById(604L)).get()
                .extracting(CatalogClient.MovieProjectionData::title, CatalogClient.MovieProjectionData::posterPath)
                .containsExactly("Obscure Film", null);
        server.verify();
    }

    @Test
    void projectionByIdReturnsEmptyOn404() {
        // A definitive "no such movie" — empty, NOT an exception (the caller serves null and won't retry).
        server.expect(requestTo(BASE + "/movies/999999")).andRespond(withResourceNotFound());

        assertThat(client.projectionById(999_999L)).isEmpty();
        server.verify();
    }

    @Test
    void projectionByIdThrowsOnUnavailableSoTheCacheIsNotPoisoned() {
        // A 5xx is an outage, not a bad id — throw so the caller writes NOTHING and retries next read.
        server.expect(requestTo(BASE + "/movies/603")).andRespond(withServerError());

        assertThatThrownBy(() -> client.projectionById(603L))
                .isInstanceOf(CatalogUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.BOOKING_CATALOG_UNAVAILABLE);
        server.verify();
    }
}
