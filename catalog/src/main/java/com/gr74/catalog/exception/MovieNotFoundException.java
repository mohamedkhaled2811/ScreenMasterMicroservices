package com.gr74.catalog.exception;

/** Thrown when no movie exists for the requested id. Maps to 404. */
public class MovieNotFoundException extends CatalogException {

    public MovieNotFoundException(Long id) {
        super(CatalogErrorCode.CATALOG_MOVIE_NOT_FOUND, "No movie found for id " + id);
    }
}
