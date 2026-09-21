package com.gr74.notification.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the recipient lookup talks to Keycloak's Admin API with.
 *
 * <p><b>Why a fully-built {@code RestClient} bean and not a {@code RestClient.Builder}.</b> Two
 * reasons, and the first one is a trap this repo has already hit twice (see
 * {@code payment/config/BookingClientConfig} and {@code booking/config/CatalogClientConfig}):
 *
 * <ol>
 *   <li><b>There is no auto-configured {@code RestClient.Builder} bean to inject.</b> Boot 4 split
 *       {@code RestClient} out of the web starter, and this service pulls it in transitively — so a
 *       constructor asking for a {@code RestClient.Builder} fails at startup with
 *       "No qualifying bean of type 'RestClient$Builder'". Building it here removes the guess.</li>
 *   <li><b>Keeping builders out of the container's reach is the house rule.</b> Eureka's own
 *       transport autowires a {@code RestClient.Builder} <em>by type</em>; publishing one as a bean
 *       is how the other two services nearly deadlocked their own registration. A built client
 *       cannot be picked up that way.</li>
 * </ol>
 *
 * <p><b>Timeouts are the point of this class, not decoration.</b> This call sits on the
 * {@code @RabbitListener} consume path: an unbounded read would let one unresponsive Keycloak pin a
 * listener thread — and its open transaction, holding the idempotency claim — indefinitely, until
 * every consumer thread is stuck and no ticket is sent at all. Bounded means the call fails fast,
 * the claim rolls back cleanly, and the broker redelivers with backoff. 2s each is generous for a
 * lookup that is a single indexed read inside our own network.
 *
 * <p>Note Keycloak is reached by its <b>direct address, not {@code lb://}</b>: it is infrastructure,
 * not one of our registered services, so there is nothing for the load balancer to resolve.
 */
@Configuration
public class KeycloakClientConfig {

    @Bean
    public RestClient keycloakRestClient(NotificationProps props) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(2))
                .withReadTimeout(Duration.ofSeconds(2));
        ClientHttpRequestFactory requestFactory =
                ClientHttpRequestFactoryBuilder.detect().build(settings);
        // A fresh builder, NOT a bean — see the class javadoc.
        return RestClient.builder()
                .baseUrl(props.identity().serverUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
