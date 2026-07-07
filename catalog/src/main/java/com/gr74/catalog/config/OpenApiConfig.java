package com.gr74.catalog.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI document metadata for the catalog service (see {@code docs/concepts/openapi-springdoc.md}).
 *
 * <p>springdoc infers the paths, the paged {@code PagedModel} response envelope, and the
 * {@code MovieFilter} query params + their Bean-Validation constraints straight from
 * {@code MovieController}. This bean supplies only what it can't infer: the title/version/description
 * and the {@code Server} URL.
 *
 * <p>The controller exposes the bare {@code /movies} path, but clients reach it through the gateway at
 * {@code /api/movies} ({@code StripPrefix=1} removes {@code /api} before forwarding). We advertise the
 * gateway base + {@code /api} as the OpenAPI {@code Server} so "Try it out" hits the real public URL —
 * see the payment service's {@code OpenApiConfig} for the full rationale.
 */
@Configuration
public class OpenApiConfig {

    // publicUrl injected on the @Bean method (not a field) per the project's no-field-injection convention.
    @Bean
    OpenAPI catalogOpenApi(@Value("${openapi.public-url:http://localhost:8080}") String publicUrl) {
        Server gateway = new Server()
                .url(publicUrl + "/api")
                .description("Public gateway (adds the /api namespace, strips it before the service)");

        return new OpenAPI()
                .info(new Info()
                        .title("ScreenMaster — Catalog API")
                        .version("v1")
                        .description("""
                                The movie catalogue. GET /movies/{id} returns full movie detail;
                                GET /movies is a paged, dynamically-filtered search (any subset of the
                                filter params, AND-combined) returning the PagedModel envelope. Data is
                                synced from TMDB.""")
                        .contact(new Contact().name("ScreenMaster").email("doublem.shared@gmail.com")))
                .servers(List.of(gateway));
    }
}
