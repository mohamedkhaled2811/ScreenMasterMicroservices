package com.gr74.booking.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.gr74.booking.security.CurrentUserArgumentResolver;

/**
 * Web-MVC wiring for the booking service. Registers the {@link CurrentUserArgumentResolver} so any
 * controller method can declare {@code @CurrentUser String userId} and receive the authenticated
 * caller's id (from the {@code X-User-Id} header today, from the JWT {@code sub} in Phase 7).
 *
 * <p>Kept separate from {@link WebPagingConfig} (which owns the paging policy) so each config reads as
 * one concern.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
