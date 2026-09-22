package com.gr74.payment.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.gr74.payment.security.UserTokenRelayInterceptor;

/**
 * Builds the outbound {@link RestClient}s for Booking (load-balanced) and for gateways (direct).
 */
@Configuration
public class BookingClientConfig {

    /** {@code lb://<service-name>} — resolved to a real instance by the load balancer. */
    private static final String BOOKING_BASE_URL = "lb://booking";

    /**
     * Booking client (2s connect / 2s read). Relays the user token, then routes via load balancer.
     */
    @Bean
    public RestClient bookingRestClient(DeferringLoadBalancerInterceptor lbInterceptor) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder()
                .baseUrl(BOOKING_BASE_URL)
                .requestInterceptor(new UserTokenRelayInterceptor())
                .requestInterceptor(lbInterceptor)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Shared builder for gateway adapters (3s connect / 10s read). Not load-balanced.
     */
    @Bean
    public RestClient.Builder gatewayRestClientBuilder() {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
