package com.gr74.booking.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
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
 *   <li><b>{@code @LoadBalanced} on the builder.</b> Spring Cloud's
 *       {@code LoadBalancerRestClientBuilderBeanPostProcessor} adds a load-balancer interceptor to any
 *       {@code @LoadBalanced} {@code RestClient.Builder}, so a {@code lb://catalog} base URL is resolved
 *       through Eureka to a healthy catalog instance's real host:port — no hard-coded address. Reachable
 *       as soon as Catalog registers; survives Catalog scaling. See {@code docs/concepts/service-discovery.md}.</li>
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

    @Bean
    @LoadBalanced
    public RestClient.Builder catalogRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient catalogRestClient(@LoadBalanced RestClient.Builder builder) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return builder
                .baseUrl(CATALOG_BASE_URL)
                .requestFactory(requestFactory)
                .build();
    }
}
