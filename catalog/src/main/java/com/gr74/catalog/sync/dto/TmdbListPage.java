package com.gr74.catalog.sync.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Inbound wire type for TMDB list endpoints. Only the ids drive the sync. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbListPage(
        int page,
        @JsonProperty("total_pages") int totalPages,
        @JsonProperty("total_results") int totalResults,
        List<Result> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(Long id) {
    }

    /** Movie ids on this page. */
    public List<Long> movieIds() {
        return results == null ? List.of() : results.stream().map(Result::id).toList();
    }
}
