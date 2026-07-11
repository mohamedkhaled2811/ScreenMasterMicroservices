package com.gr74.booking.client;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.exception.MovieNotInCatalogException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Booking's synchronous window into Catalog. It carries the two cross-service reads Booking needs, and
 * they answer failure <em>differently on purpose</em> — the contrast is the whole point:
 * <ul>
 *   <li>{@link #verifyMovieExists(long)} (write path, showtime create) <b>throws</b> on any failure — a
 *       bad {@code movieId} must reject the write, so "unknown" and "Catalog down" are both hard stops.</li>
 *   <li>{@link #titlesByIds(Set)} (read path, "my bookings") <b>degrades</b> — a Catalog outage returns
 *       an empty map, so the bookings list still renders (with null titles) instead of failing. A read is
 *       more useful stale/partial than absent; a write is not.</li>
 * </ul>
 *
 * <p>The write path makes the cross-service cut feel real: the {@code movie_id} FK is gone, so Booking
 * asks Catalog over HTTP whether the id exists.
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

    /**
     * Resolve a set of movie ids to their titles in <em>one</em> call — the read side of the M2
     * composition ("my bookings" needs a title per booking). Batching is the fix for the network N+1:
     * one {@code GET /movies/batch?ids=…} for the whole page, not one {@code GET /movies/{id}} per row.
     *
     * <p><b>Degrades, never throws.</b> Unlike {@link #verifyMovieExists}, a Catalog failure here (5xx,
     * timeout, transport) is swallowed to an <em>empty map</em> with a warning: the caller then renders
     * bookings with {@code movieTitle: null} rather than failing the whole list. Ids Catalog doesn't
     * return are simply absent from the map (the caller treats a miss as "title unknown"). An empty input
     * short-circuits with no network call.
     *
     * @return an {@code id → title} map; entries only for ids Catalog returned (may be empty)
     */
    public Map<Long, String> titlesByIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        try {
            List<MovieSummary> movies = catalogRestClient.get()
                    .uri(uri -> buildBatchUri(uri, ids))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<MovieSummary>>() {});
            if (movies == null) {
                return Map.of();
            }
            return movies.stream()
                    .filter(m -> m.id() != null && m.title() != null)
                    .collect(Collectors.toMap(MovieSummary::id, MovieSummary::title, (a, b) -> a));
        } catch (RuntimeException failure) {
            // Read-path degrade: log and return empty so "my bookings" still answers with null titles.
            // (Contrast verifyMovieExists, which throws — a write must reject a bad/unverifiable id.)
            log.warn("Catalog unavailable while resolving {} title(s); degrading to no titles: {}",
                    ids.size(), failure.getMessage());
            return Map.of();
        }
    }

    /** {@code /movies/batch?ids=1&ids=2&…} — each id its own query param so binding to {@code List<Long>} is unambiguous. */
    private static URI buildBatchUri(UriBuilder uri, Set<Long> ids) {
        uri.path("/movies/batch");
        ids.forEach(id -> uri.queryParam("ids", id));
        return uri.build();
    }

    /** Minimal projection of Catalog's {@code MovieSummaryDto} — Booking only needs the id and title here. */
    record MovieSummary(Long id, String title) {
    }
}
