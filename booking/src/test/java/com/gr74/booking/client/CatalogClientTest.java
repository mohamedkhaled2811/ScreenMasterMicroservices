package com.gr74.booking.client;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
}
