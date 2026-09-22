package com.gr74.booking.config;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Paging policy: {@code PagedModel} envelope with default size 20 and max size 100.
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
