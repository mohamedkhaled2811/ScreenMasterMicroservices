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
 * Builds the {@link RestClient} Booking uses to reach Catalog when validating a showtime's
 * {@code movieId} (plan option 5C, chosen path).
 *
 * <p>Two things make this work across services:
 * <ul>
 *   <li><b>The load-balancer interceptor, applied to a <em>private</em> builder.</b> To resolve a
 *       {@code lb://catalog} base URL through Eureka to a healthy catalog instance's real host:port
 *       (no hard-coded address; reachable as soon as Catalog registers; survives Catalog scaling —
 *       see {@code docs/concepts/service-discovery.md}), the request must pass through Spring Cloud's
 *       load-balancer interceptor. We inject the framework's {@link DeferringLoadBalancerInterceptor}
 *       bean and attach it to a fresh {@code RestClient.builder()} created <em>inside</em> this method.
 *       <p><b>Why not {@code @LoadBalanced} on a builder bean?</b> {@code @LoadBalanced} works by having
 *       a {@code BeanPostProcessor} mutate every {@code RestClient.Builder} <em>bean</em> marked with it.
 *       But on the Boot 4 / Spring Cloud 2025.1.2 stack, Eureka's own transport
 *       ({@code RestClientEurekaHttpClient}) autowires a {@code RestClient.Builder} <em>by type</em> from
 *       the context. If our load-balanced builder is the (only) such bean, Eureka builds its registry
 *       client on top of it and inherits the {@code lb://} interceptor — so Eureka tries to
 *       load-balance its own {@code http://discovery:8761} call, re-enters the still-initializing
 *       {@code eurekaClient} bean, and dies with {@code BeanCurrentlyInCreationException}; Booking then
 *       never registers. Keeping the load-balanced builder <em>local</em> (never a bean) means Eureka's
 *       transport never sees it. See {@code docs/concepts/service-discovery.md}.</li>
 *   <li><b>A bounded timeout.</b> Validating on the write path means {@code POST /showtimes} now waits on
 *       Catalog; without a timeout a hung Catalog would hang showtime creation indefinitely. We cap
 *       connect + read so a slow/dead Catalog fails fast into a {@code BOOKING_CATALOG_UNAVAILABLE} (503)
 *       instead. This is the temporal-coupling cost of 5C, contained. Full Resilience4j (retry, breaker)
 *       lands in Phase 5; a hard timeout is the honest minimum for now.</li>
 * </ul>
 */
@Configuration
public class CatalogClientConfig {

    /** {@code lb://<service-name>} — resolved to a real instance by the load balancer. */
    private static final String CATALOG_BASE_URL = "lb://catalog";

    /**
     * {@code lbInterceptor} is the framework-registered {@link DeferringLoadBalancerInterceptor} bean; it
     * lazily resolves the real blocking interceptor on first use, so it is safe to inject even though this
     * config declares no {@code @LoadBalanced} builder of its own.
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
