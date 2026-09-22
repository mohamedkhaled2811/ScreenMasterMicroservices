package com.gr74.catalog.sync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Inbound wire type for a TMDB genre. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbGenre(Long id, String name) {
}
