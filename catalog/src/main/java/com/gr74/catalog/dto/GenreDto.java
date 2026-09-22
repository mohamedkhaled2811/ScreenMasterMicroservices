package com.gr74.catalog.dto;

import com.gr74.catalog.model.Genre;

/** Wire type for a genre. */
public record GenreDto(Long id, String name) {

    public static GenreDto from(Genre genre) {
        return new GenreDto(genre.getId(), genre.getName());
    }
}
