package com.gr74.catalog.sync.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound wire type for TMDB's {@code GET /genre/movie/list} — the full genre vocabulary, synced once
 * up front so movie→genre links always resolve to a known {@code genres} row.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenreList(List<TmdbGenre> genres) {
}
