package com.gr74.catalog.sync.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound wire type for TMDB's list endpoints ({@code /movie/popular}, {@code /movie/top_rated},
 * {@code /movie/now_playing}) — a page of movie summaries plus paging metadata.
 *
 * <p>The sync only reads each result's {@code id} from here (to then hydrate the full movie via
 * {@code GET /movie/{id}}) and the {@code totalPages}/{@code page} to drive the resumable walk. We
 * deliberately do <em>not</em> persist from the summary: TMDB's list rows omit fields the
 * {@code movies} table wants (runtime, tagline, status), so a detail fetch is the source of truth.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbListPage(
        int page,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("total_results") int totalResults,
        List<Result> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Long id) {
    }

    /** The movie ids on this page (the only thing the sync needs from a list result). */
    public List<Long> movieIds() {
        return results == null ? List.of() : results.stream().map(Result::id).toList();
    }
}
