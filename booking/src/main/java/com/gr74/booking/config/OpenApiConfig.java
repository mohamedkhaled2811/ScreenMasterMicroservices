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
 * OpenAPI document metadata for the booking service (see {@code docs/concepts/openapi-springdoc.md}).
 *
 * <p>springdoc infers the paths, the paged {@code PagedModel} listing responses, the raw-{@code List}
 * showtime reads, and the create-request DTO schemas + constraints from {@code TheaterController} and
 * {@code ShowtimeController}. This bean supplies the title/version/description and the {@code Server}
 * URL only.
 *
 * <p>The controllers expose bare paths ({@code /theaters}, {@code /showtimes}); clients reach them
 * through the gateway at {@code /api/theaters}, {@code /api/showtimes} ({@code StripPrefix=1} removes
 * {@code /api}). We advertise the gateway base + {@code /api} as the OpenAPI {@code Server} so "Try it
 * out" hits the real public URL — see the payment service's {@code OpenApiConfig} for the full rationale.
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
