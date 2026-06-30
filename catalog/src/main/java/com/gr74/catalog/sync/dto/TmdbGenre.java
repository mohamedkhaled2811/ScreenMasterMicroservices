package com.gr74.catalog.sync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Inbound wire type for a TMDB genre — appears nested in {@code GET /movie/{id}} and as the list from
 * {@code GET /genre/movie/list}. The {@code id} is TMDB's assigned genre id (becomes our PK).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenre(Long id, String name) {
}
