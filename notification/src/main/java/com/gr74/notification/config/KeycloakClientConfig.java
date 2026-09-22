package com.gr74.notification.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the RestClient for Keycloak's Admin API with bounded timeouts so a slow IdP fails fast.
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
        // Fresh builder, not a bean.
        return RestClient.builder()
                .baseUrl(props.identity().serverUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
