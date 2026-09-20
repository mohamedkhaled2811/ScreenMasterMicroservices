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
 * Builds the {@link RestClient}s this service makes outbound calls with: one to Booking (via the
 * registry) and one to the payment gateways (straight out to the internet).
 *
 * <p><b>The load-balanced builder is deliberately NOT a bean</b> — the same trap Booking's
 * {@code CatalogClientConfig} documents at length. On the Boot 4 / Spring Cloud 2025.1.2 stack,
 * Eureka's own transport autowires a {@code RestClient.Builder} <em>by type</em>; if a
 * {@code @LoadBalanced} builder were the bean it found, Eureka would try to load-balance its own
 * {@code http://discovery:8761} call, re-enter the still-initializing {@code eurekaClient} bean, and
 * fail with {@code BeanCurrentlyInCreationException} — the service would never register. Creating the
 * builder locally inside the factory method keeps it out of Eureka's reach.
 *
 * <p>Both clients are <b>bounded by timeouts</b>, for the same reason and with different numbers:
 * <ul>
 *   <li><b>Booking</b> (2s connect / 2s read) sits on the synchronous payment path, and the user is
 *       waiting on a checkout redirect. A hung Booking must fail fast into a coded 503.</li>
 *   <li><b>Gateways</b> get a longer read timeout (10s) because a real payment gateway legitimately
 *       takes seconds to open a session — but it is still bounded, because an unbounded HTTP client is
 *       how one slow third party exhausts the whole thread pool.</li>
 * </ul>
 * Resilience4j (retry, breaker, per-gateway bulkhead) layers on top; these timeouts are the
 * floor beneath it.
 *
 * <p>The Booking client additionally carries a {@link UserTokenRelayInterceptor}: the payability read runs <em>on behalf of the user, inside the user's request</em>, so the
 * user's own Bearer token rides along and Booking's ownership answer is a genuine authorization
 * check rather than Payment's assertion. The interceptor is a plain {@code new} (not a bean) for
 * the same Eureka reason as the builder — and being stateless, one instance is safe to share.
 */
@Configuration
public class BookingClientConfig {

    /** {@code lb://<service-name>} — resolved to a real instance by the load balancer. */
    private static final String BOOKING_BASE_URL = "lb://booking";

    /**
     * Payment's window into Booking. {@code lbInterceptor} is the framework-registered
     * {@link DeferringLoadBalancerInterceptor} bean; it resolves the real blocking interceptor lazily,
     * so injecting it here is safe even though this config declares no {@code @LoadBalanced} builder.
     *
     * <p>The {@link UserTokenRelayInterceptor} runs <em>before</em> the load-balancer interceptor:
     * the credential is attached while the request is still the logical {@code lb://booking} call,
     * and routing resolves afterwards. Order between them is not load-bearing (headers survive
     * routing either way), but "authenticate, then route" reads in the order the hop happens.
     */
    @Bean
    public RestClient bookingRestClient(DeferringLoadBalancerInterceptor lbInterceptor) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder()          // fresh, NOT a bean — Eureka's transport can never autowire it
                .baseUrl(BOOKING_BASE_URL)
                .requestInterceptor(new UserTokenRelayInterceptor())
                .requestInterceptor(lbInterceptor)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * The builder the gateway adapters build their clients from. Deliberately <em>not</em>
     * load-balanced: Stripe and Paymob are on the public internet, not in our registry, so an
     * {@code lb://} interceptor would be meaningless here.
     *
     * <p>This one <em>is</em> a bean because the adapters inject it, which raises the same Eureka
     * concern as above — but harmlessly: it carries no load-balancer interceptor, so if Eureka's
     * transport does autowire it, all it inherits is a sane timeout.
     */
    @Bean
    public RestClient.Builder gatewayRestClientBuilder() {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(3))
                .withReadTimeout(Duration.ofSeconds(10)); // gateways are legitimately slow, but bounded
        return RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
