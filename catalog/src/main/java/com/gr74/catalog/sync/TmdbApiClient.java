package com.gr74.catalog.sync;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.gr74.catalog.exception.TmdbSyncException;
import com.gr74.catalog.model.Genre;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.model.SyncType;
import com.gr74.catalog.sync.dto.TmdbChangesPage;
import com.gr74.catalog.sync.dto.TmdbGenre;
import com.gr74.catalog.sync.dto.TmdbGenreList;
import com.gr74.catalog.sync.dto.TmdbListPage;
import com.gr74.catalog.sync.dto.TmdbMovieDetails;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Thin wrapper over TMDB's HTTP API. Holds no sync state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TmdbApiClient {

    private final RestClient tmdbRestClient;

    /** TMDB's change feed expects ISO {@code yyyy-MM-dd} (UTC) for {@code start_date}/{@code end_date}. */
    private static final DateTimeFormatter TMDB_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Fetch the full movie-genre vocabulary ({@code GET /genre/movie/list}). */
    public List<TmdbGenre> genres() {
        try {
            TmdbGenreList body = tmdbRestClient.get()
                    .uri("/genre/movie/list")
                    .retrieve()
                    .body(TmdbGenreList.class);
            return body == null || body.genres() == null ? List.of() : body.genres();
        } catch (RestClientException e) {
            throw new TmdbSyncException("Failed to fetch TMDB genre list", e);
        }
    }

    /** Fetch one page of a movie list ({@code GET /movie/{popular|top_rated|now_playing}?page=N}). */
    public TmdbListPage listPage(SyncType type, int page) {
        try {
            TmdbListPage body = tmdbRestClient.get()
                    .uri(uri -> uri.path(type.path()).queryParam("page", page).build())
                    .retrieve()
                    .body(TmdbListPage.class);
            if (body == null) {
                throw new TmdbSyncException("TMDB returned an empty body for " + type + " page " + page);
            }
            return body;
        } catch (RestClientException e) {
            throw new TmdbSyncException("Failed to fetch TMDB " + type + " page " + page, e);
        }
    }

    /**
     * Fetch one page of the change feed ({@code GET /movie/changes?start_date=&end_date=&page=}).
     */
    public TmdbChangesPage changedMovieIds(LocalDate start, LocalDate end, int page) {
        try {
            TmdbChangesPage body = tmdbRestClient.get()
                    .uri(uri -> uri.path("/movie/changes")
                            .queryParam("start_date", TMDB_DATE.format(start))
                            .queryParam("end_date", TMDB_DATE.format(end))
                            .queryParam("page", page)
                            .build())
                    .retrieve()
                    .body(TmdbChangesPage.class);
            if (body == null) {
                throw new TmdbSyncException(
                        "TMDB returned an empty body for changes " + start + ".." + end + " page " + page);
            }
            return body;
        } catch (RestClientException e) {
            throw new TmdbSyncException(
                    "Failed to fetch TMDB changes " + start + ".." + end + " page " + page, e);
        }
    }

    /** Fetch a single movie's full details ({@code GET /movie/{id}}). */
    public TmdbMovieDetails movieDetails(long id) {
        try {
            TmdbMovieDetails body = tmdbRestClient.get()
                    .uri("/movie/{id}", id)
                    .retrieve()
                    .body(TmdbMovieDetails.class);
            if (body == null) {
                throw new TmdbSyncException("TMDB returned an empty body for movie " + id);
            }
            return body;
        } catch (RestClientException e) {
            throw new TmdbSyncException("Failed to fetch TMDB movie " + id, e);
        }
    }

    /**
     * Map a TMDB details payload onto a {@link Movie} entity.
     */
    public Movie toMovie(TmdbMovieDetails d, Set<Genre> resolvedGenres) {
        Movie movie = new Movie(d.id(), d.title());
        movie.applyDetails(b -> b
                .originalTitle(d.originalTitle())
                .overview(d.overview())
                .tagline(blankToNull(d.tagline()))
                .releaseDate(parseDate(d.releaseDate()))
                .runtime(d.runtime())
                .status(d.status())
                .originalLanguage(d.originalLanguage())
                .popularity(d.popularity())
                .voteAverage(d.voteAverage())
                .voteCount(d.voteCount())
                .posterPath(d.posterPath())
                .backdropPath(d.backdropPath())
                .adult(d.adult()));
        movie.setGenres(new LinkedHashSet<>(resolvedGenres));
        return movie;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            log.warn("Unparseable TMDB release_date '{}' — storing null", value);
            return null;
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
