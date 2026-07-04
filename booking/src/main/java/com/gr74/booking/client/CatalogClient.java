package com.gr74.booking.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.exception.MovieNotInCatalogException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Booking's synchronous window into Catalog — used only to validate a showtime's {@code movieId} at
 * create time (plan option 5C, chosen path). This is the code that makes the cross-service cut feel
 * real: the {@code movie_id} FK is gone, so Booking asks Catalog over HTTP whether the id exists.
 *
 * <p>It reads {@code GET /movies/{id}} on {@code lb://catalog} (Eureka-resolved,
 * {@link com.gr74.booking.config.CatalogClientConfig}) and maps the three outcomes to the error
 * contract:
 * <ul>
 *   <li><b>2xx</b> → the movie exists; return quietly.</li>
 *   <li><b>404</b> → Catalog answered "no such movie" → {@link MovieNotInCatalogException} (404 to our
 *       caller). A definitive "bad id" — retrying won't help.</li>
 *   <li><b>anything else / transport failure / timeout</b> → we couldn't get a trustworthy answer →
 *       {@link CatalogUnavailableException} (503). An outage — retrying later might help. Keeping this
 *       distinct from the 404 is the whole point: a bad id and a down dependency are different problems.</li>
 * </ul>
 *
 * <p>The two failure modes are the temporal-coupling cost of validating on the write path — see the
 * javadoc on {@link CatalogUnavailableException}. Booking-side Resilience4j (retry/breaker) is Phase 5;
 * for now the bounded client timeout keeps a hung Catalog from hanging showtime creation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogClient {

    private final RestClient catalogRestClient;

    /**
     * Verify that Catalog knows the given movie id. Returns normally if it does; throws a coded
     * exception otherwise. We don't need the movie body here — only its existence — so we discard it.
     */
    public void verifyMovieExists(long movieId) {
        try {
            catalogRestClient.get()
                    .uri("/movies/{id}", movieId)
                    .retrieve()
                    // Handle 404 ourselves so it becomes a definitive "no such movie", not an outage.
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            throw new MovieNotInCatalogException(movieId);
                        }
                        // Any other 4xx (e.g. a 400 from a malformed id) is our problem to reason about,
                        // not the caller's — surface it as "couldn't validate" rather than "bad movie".
                        throw new CatalogUnavailableException(movieId,
                                new IllegalStateException("Unexpected " + response.getStatusCode()
                                        + " from Catalog while validating movieId=" + movieId));
                    })
                    .toBodilessEntity();
            log.debug("Catalog confirmed movieId={} exists", movieId);
        } catch (MovieNotInCatalogException | CatalogUnavailableException known) {
            throw known; // already coded — don't re-wrap
        } catch (RestClientResponseException http) {
            // A 5xx from Catalog (it's up but erroring): treat as unavailable, not "no such movie".
            throw new CatalogUnavailableException(movieId, http);
        } catch (RuntimeException transport) {
            // Connection refused, timeout, DNS/lb resolution failure — Catalog is unreachable.
            throw new CatalogUnavailableException(movieId, transport);
        }
    }
}
