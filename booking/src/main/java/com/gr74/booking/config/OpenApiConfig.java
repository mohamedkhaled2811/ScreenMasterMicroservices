package com.gr74.booking.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI metadata for the booking service; advertises the gateway URL so "Try it out" hits the public path.
 */
@Configuration
public class OpenApiConfig {

    // publicUrl injected on the @Bean method (not a field) per the project's no-field-injection convention.
    @Bean
    OpenAPI bookingOpenApi(@Value("${openapi.public-url:http://localhost:8080}") String publicUrl) {
        Server gateway = new Server()
                .url(publicUrl + "/api")
                .description("Public gateway (adds the /api namespace, strips it before the service)");

        return new OpenAPI()
                .info(new Info()
                        .title("ScreenMaster — Booking API")
                        .version("v1")
                        .description("""
                                The inventory tree (theaters -> screens -> seats), seat types, and
                                showtimes. Build order is a tree: a seat type + a theater first, then a
                                screen, then seats / showtimes (creating a child before its parent 404s).
                                Listings are paged (PagedModel envelope); showtime reads return plain
                                arrays. Creating a showtime validates its movieId live against the Catalog
                                service.""")
                        .contact(new Contact().name("ScreenMaster").email("doublem.shared@gmail.com")))
                .servers(List.of(gateway));
    }
}
