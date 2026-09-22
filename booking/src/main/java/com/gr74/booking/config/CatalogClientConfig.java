package com.gr74.booking.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} Booking uses to validate a showtime's {@code movieId} against Catalog.
 * Resolves {@code lb://catalog} through Eureka with a bounded connect/read timeout.
 * Uses a local builder (never a bean) so Eureka's own transport never inherits the load-balancer interceptor.
 */
@Configuration
public class CatalogClientConfig {

    /** {@code lb://<service-name>} — resolved to a real instance by the load balancer. */
    private static final String CATALOG_BASE_URL = "lb://catalog";

    /**
     * Framework-registered load-balancer interceptor; safe to inject with no {@code @LoadBalanced} builder bean.
     */
    @Bean
    public RestClient catalogRestClient(DeferringLoadBalancerInterceptor lbInterceptor) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder()          // fresh, NOT a bean — Eureka's transport can never autowire it
                .baseUrl(CATALOG_BASE_URL)
                .requestInterceptor(lbInterceptor)
                .requestFactory(requestFactory)
                .build();
    }
}
