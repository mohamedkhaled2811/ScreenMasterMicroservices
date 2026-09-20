package com.gr74.payment.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.gr74.payment.security.CurrentUserArgumentResolver;

/**
 * Web-MVC wiring for the payment service. Registers the {@link CurrentUserArgumentResolver} so any
 * controller method can declare {@code @CurrentUser String userId} and receive the authenticated
 * caller's id — the verified JWT {@code sub}, never a client-set header.
 *
 * <p>Kept as its own config (one concern per config) rather than folded into
 * {@code SecurityConfig}: this is MVC argument resolution, not the filter chain.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
