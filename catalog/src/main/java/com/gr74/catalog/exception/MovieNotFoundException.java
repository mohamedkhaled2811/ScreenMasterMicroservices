package com.gr74.catalog.exception;

/**
 * Thrown when a movie is looked up by an id that has no row. Maps to
 * {@link CatalogErrorCode#CATALOG_MOVIE_NOT_FOUND} (HTTP 404) via {@link GlobalExceptionHandler}.
 */
public class MovieNotFoundException extends CatalogException {

    public MovieNotFoundException(Long id) {
        super(CatalogErrorCode.CATALOG_MOVIE_NOT_FOUND, "No movie found for id " + id);
    }
}
