package com.gr74.booking.config;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Repo-wide paging policy for the booking service (see {@code docs/concepts/pagination-and-filtering.md}).
 *
 * <p>Two jobs:
 * <ul>
 *   <li><b>Stable JSON contract</b> — {@code pageSerializationMode = VIA_DTO} makes every
 *       {@code Page<…>} a controller returns serialize as Spring Data's {@code PagedModel} envelope
 *       ({@code content} + a nested {@code page: {size, number, totalElements, totalPages}}) instead of
 *       the internal {@code PageImpl} shape. The default serialization is explicitly documented as
 *       unstable across versions ("please use PagedModel … VIA_DTO"); pinning it here means the paging
 *       contract is the same, versioned shape on every list endpoint.</li>
 *   <li><b>A hard ceiling on page size</b> — the {@link PageableHandlerMethodArgumentResolverCustomizer}
 *       clamps {@code ?size=} to {@link #MAX_PAGE_SIZE} and defaults an absent size to
 *       {@link #DEFAULT_PAGE_SIZE}. This is the teeth behind the "listings paginate; they never dump"
 *       convention: a client cannot ask for {@code size=1000000} and force the service to load and
 *       serialize the whole table. Pages stay 0-indexed (Spring's default) — we did not enable
 *       one-indexed params.</li>
 * </ul>
 *
 * <p>Setting the caps in code (a customizer bean) rather than only via {@code spring.data.web.pageable.*}
 * properties guarantees the ceiling regardless of property-binding differences across Boot versions,
 * and keeps the policy visible next to the reason for it.
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
            resolver.setFallbackPageable(org.springframework.data.domain.PageRequest.of(0, DEFAULT_PAGE_SIZE));
            resolver.setMaxPageSize(MAX_PAGE_SIZE);
        };
    }
}
