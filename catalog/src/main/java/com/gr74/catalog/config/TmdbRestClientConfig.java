package com.gr74.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} the TMDB sync uses, pre-baked with TMDB's base URL and the v4 bearer
 * token so call sites only specify the path (e.g. {@code .uri("/movie/{id}", id)}).
 *
 * <p>The token is read from {@link TmdbProps#accessToken()} ({@code TMDB_API_KEY} env var). We set it
 * as a default {@code Authorization: Bearer ...} header here, once, rather than on every request —
 * the modern TMDB auth style (v4), preferred over the legacy {@code ?api_key=} query param.
 *
 * <p>This is a dedicated {@code @Bean} (not a field-built client) so it's a managed singleton the
 * {@code TmdbApiClient} can inject, and so a test can swap in a {@code MockRestServiceServer}-backed
 * client if ever needed. See {@code docs/concepts/spring-web-annotations.md} (RestClient).
 */
@Configuration
public class TmdbRestClientConfig {

    @Bean
    public RestClient tmdbRestClient(TmdbProps props) {
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.accessToken())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
