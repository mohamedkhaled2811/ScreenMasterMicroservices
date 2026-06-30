package com.gr74.catalog.sync.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Inbound wire type for TMDB's change feed ({@code GET /movie/changes}) — one page of the ids of
 * movies edited inside the requested UTC date window, plus paging metadata.
 *
 * <p>This is the freshness counterpart to {@link TmdbListPage}: where the list pages drive the
 * one-time <em>backfill</em>, the changes feed drives the ongoing <em>incremental refresh</em>. The
 * sync reads only each result's {@code id} (to re-hydrate the full movie via {@code GET /movie/{id}}
 * if we already store it) and {@code totalPages}/{@code page} to walk a busy day across ticks. We
 * never persist from this payload directly — a detail fetch is always the source of truth.
 *
 * <p>TMDB serves only roughly the last 14 days of changes, so the cursor that consumes this can never
 * "catch up" a gap older than that — a bounded-history limitation the sync clamps against.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbChangesPage(
        int page,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("total_results") int totalResults,
        List<Result> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Long id) {
    }

    /** The changed movie ids on this page (the only thing the refresh needs from a result). */
    public List<Long> movieIds() {
        return results == null ? List.of() : results.stream().map(Result::id).toList();
    }
}
