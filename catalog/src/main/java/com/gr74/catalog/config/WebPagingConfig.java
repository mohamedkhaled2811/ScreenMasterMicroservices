package com.gr74.catalog.config;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Repo-wide paging policy for the catalog service (see {@code docs/concepts/pagination-and-filtering.md}).
 *
 * <p>{@code pageSerializationMode = VIA_DTO} makes {@code GET /movies} serialize its {@code Page} as
 * Spring Data's stable {@code PagedModel} envelope instead of the internal {@code PageImpl} shape —
 * this silences the "please use PagedModel … VIA_DTO" instability warning the search endpoint emitted,
 * and matches booking's paging contract. The {@link PageableHandlerMethodArgumentResolverCustomizer}
 * caps {@code ?size=} so no caller can force the service to load and serialize the whole catalogue.
 * Pages stay 0-indexed (Spring's default).
 */
@Configuration
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class WebPagingConfig {

    /** Page size used when the caller sends no {@code size} param. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Absolute upper bound on {@code size}; a larger request is clamped down to this. */
    public static final int MAX_PAGE_SIZE = 100;

    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer() {
        return resolver -> {
            resolver.setFallbackPageable(PageRequest.of(0, DEFAULT_PAGE_SIZE));
            resolver.setMaxPageSize(MAX_PAGE_SIZE);
        };
    }
}
