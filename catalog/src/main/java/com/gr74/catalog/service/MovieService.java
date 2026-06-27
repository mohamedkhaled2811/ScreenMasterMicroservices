package com.gr74.catalog.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.catalog.exception.MovieNotFoundException;
import com.gr74.catalog.model.Movie;
import com.gr74.catalog.repository.MovieRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-side business logic for the movie catalogue.
 *
 * <p>{@link #getById(Long)} runs in a read-only transaction so the repository's genres-fetch happens
 * inside an open session (we run with {@code open-in-view: false}); the mapper to a DTO can then read
 * {@code genres} without a {@code LazyInitializationException}. A missing movie is a coded domain
 * error ({@link MovieNotFoundException} → 404), not a null the controller has to special-case.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MovieService {

    private final MovieRepository movieRepository;

    @Transactional(readOnly = true)
    public Movie getById(Long id) {
        return movieRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Movie not found for id={}", id);
                    return new MovieNotFoundException(id);
                });
    }
}
