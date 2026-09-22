package com.gr74.booking.client;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Booking's synchronous reads from Catalog. Each method answers failure differently:
 * verify throws on any failure, titles degrades to empty, projection returns empty
 * on 404 but throws on outage (so outages are never cached).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogClient {

    private final RestClient catalogRestClient;

    /**
     * Verify Catalog knows the movie id. Returns normally if so; throws a coded
     * exception otherwise. The body is discarded — only existence matters.
     */
    public void verifyMovieExists(long movieId) {
        try {
            catalogRestClient.get()
                    .uri("/movies/{id}", movieId)
                    .retrieve()
                    // Handle 404 as "no such movie", not an outage.
                    .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                        if (response.getStatusCode().value() == 404) {
                            throw new MovieNotInCatalogException(movieId);
                        }
                        // Any other 4xx is surfaced as "couldn't validate".
                        throw new CatalogUnavailableException(movieId,
                                new IllegalStateException("Unexpected " + response.getStatusCode()
                                        + " from Catalog while validating movieId=" + movieId));
                    })
                    .toBodilessEntity();
            log.debug("Catalog confirmed movieId={} exists", movieId);
        } catch (MovieNotInCatalogException | CatalogUnavailableException known) {
            throw known;
        } catch (RestClientResponseException http) {
            // 5xx from Catalog: unavailable, not "no such movie".
            throw new CatalogUnavailableException(movieId, http);
        } catch (RuntimeException transport) {
            // Connection refused, timeout, DNS/lb failure: Catalog unreachable.
            throw new CatalogUnavailableException(movieId, transport);
        }
    }

    /**
     * Resolve ids to titles in one call. Degrades to an empty map on failure so the
     * list still renders with null titles; unknown ids are simply absent.
     * @return id → title map, entries only for ids Catalog returned
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
            // Read-path degrade: log and return empty so the list still answers.
            log.warn("Catalog unavailable while resolving {} title(s); degrading to no titles: {}",
                    ids.size(), failure.getMessage());
            return Map.of();
        }
    }

    /**
     * Resolve one movie id to its cached fields for a backfill. 404 → empty;
     * anything else failing throws so the caller does not cache the outage.
     */
    public Optional<MovieProjectionData> projectionById(long movieId) {
        try {
            MovieSummary movie = catalogRestClient.get()
                    .uri("/movies/{id}", movieId)
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new MovieNotInCatalogException(movieId);
                    })
                    .body(MovieSummary.class);
            return Optional.ofNullable(movie)
                    .filter(m -> m.title() != null)
                    .map(m -> new MovieProjectionData(m.title(), m.posterPath()));
        } catch (MovieNotInCatalogException notFound) {
            return Optional.empty();
        } catch (RestClientResponseException http) {
            // Other HTTP errors: unavailable, must not be cached.
            throw new CatalogUnavailableException(movieId, http);
        } catch (RuntimeException transport) {
            // Connection refused, timeout, DNS/lb failure: Catalog unreachable.
            throw new CatalogUnavailableException(movieId, transport);
        }
    }

    /** {@code /movies/batch?ids=1&ids=2&…} — each id its own query param so binding to {@code List<Long>} is unambiguous. */
    private static URI buildBatchUri(UriBuilder uri, Set<Long> ids) {
        uri.path("/movies/batch");
        ids.forEach(id -> uri.queryParam("ids", id));
        return uri.build();
    }

    /** Minimal projection of Catalog's movie: the id, title, and poster path Booking needs. */
    record MovieSummary(Long id, String title, String posterPath) {
    }

    /**
     * What Booking caches about a Catalog movie — one {@code movie_projections} row.
     * @param title the movie's title
     * @param posterPath artwork path, null when the movie has no poster
     */
    public record MovieProjectionData(String title, String posterPath) {
    }
}
