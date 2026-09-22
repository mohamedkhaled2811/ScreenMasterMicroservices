package com.gr74.catalog.sync.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Inbound wire type for TMDB's {@code GET /genre/movie/list}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenreList(List<TmdbGenre> genres) {
}
