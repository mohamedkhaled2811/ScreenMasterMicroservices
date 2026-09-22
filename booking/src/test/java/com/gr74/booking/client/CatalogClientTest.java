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
 * {@link CatalogClient} against a mocked Catalog: movie validation and title/projection reads.
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
        // A non-404 client error is an availability problem, not a bad id.
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
        // One call for the whole set, hitting /movies/batch with the ids as query params.
        server.expect(ExpectedCount.once(), requestToUriTemplate(BASE + "/movies/batch?ids=603&ids=550"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("ids", "603", "550"))
                .andRespond(withSuccess(
                        "[{\"id\":603,\"title\":\"The Matrix\"},{\"id\":550,\"title\":\"Fight Club\"}]",
                        MediaType.APPLICATION_JSON));

        // A LinkedHashSet keeps the query-param order deterministic.
        Map<Long, String> titles = client.titlesByIds(new java.util.LinkedHashSet<>(java.util.List.of(603L, 550L)));

        assertThat(titles).containsEntry(603L, "The Matrix").containsEntry(550L, "Fight Club");
        server.verify();
    }

    @Test
    void titlesByIdsDegradesToEmptyMapWhenCatalogIsDown() {
        // The read path must not throw — an outage returns no titles.
        server.expect(requestTo(BASE + "/movies/batch?ids=603")).andRespond(withServerError());

        Map<Long, String> titles = client.titlesByIds(Set.of(603L));

        assertThat(titles).isEmpty();
        server.verify();
    }

    @Test
    void titlesByIdsShortCircuitsOnEmptyInputWithNoCall() {
        // No ids means no network call.
        assertThat(client.titlesByIds(Set.of())).isEmpty();
        server.verify();
    }

    // ---- projectionById (lazy-backfill cache fill) ---------------------------------------------

    @Test
    void projectionByIdReturnsTitleAndPosterOn200() {
        server.expect(requestTo(BASE + "/movies/603"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"id\":603,\"title\":\"The Matrix\",\"posterPath\":\"/matrix.jpg\"}",
                        MediaType.APPLICATION_JSON));

        // One fetch fills both projected columns.
        assertThat(client.projectionById(603L)).get()
                .extracting(CatalogClient.MovieProjectionData::title, CatalogClient.MovieProjectionData::posterPath)
                .containsExactly("The Matrix", "/matrix.jpg");
        server.verify();
    }

    @Test
    void projectionByIdKeepsTitleWhenTheMovieHasNoPoster() {
        // A null poster must not suppress the title.
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
        // A definitive "no such movie" — empty, not an exception.
        server.expect(requestTo(BASE + "/movies/999999")).andRespond(withResourceNotFound());

        assertThat(client.projectionById(999_999L)).isEmpty();
        server.verify();
    }

    @Test
    void projectionByIdThrowsOnUnavailableSoTheCacheIsNotPoisoned() {
        // A 5xx is an outage — throw so the caller writes nothing and retries next read.
        server.expect(requestTo(BASE + "/movies/603")).andRespond(withServerError());

        assertThatThrownBy(() -> client.projectionById(603L))
                .isInstanceOf(CatalogUnavailableException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.BOOKING_CATALOG_UNAVAILABLE);
        server.verify();
    }
}
