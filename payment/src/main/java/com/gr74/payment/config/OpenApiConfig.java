 package com.gr74.payment.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;

/**
 * OpenAPI document metadata for the payment service (see {@code docs/concepts/openapi-springdoc.md}).
 *
 * <p>springdoc infers <em>most</em> of the spec — paths, request/response schemas, and Bean-Validation
 * constraints — straight from {@code PaymentController} and its record DTOs. This bean only supplies the
 * things it can't infer: the document title/version/description and, crucially, the <strong>server
 * URL</strong>.
 *
 * <p><b>Why the server URL matters here.</b> The controller exposes the <em>bare</em> path
 * {@code /payments}; springdoc, running inside the service, only knows that bare path. But clients never
 * call the service directly — they go through the gateway, which serves {@code /api/payments/**} and
 * strips {@code /api} via {@code StripPrefix=1} before forwarding. So the path the frontend must call
 * ({@code /api/payments}) is not the path this service knows about ({@code /payments}). We reconcile the
 * two by advertising the gateway base + {@code /api} as the OpenAPI {@code Server}: the operation path
 * stays {@code /payments}, the server prefix supplies {@code /api}, and "Try it out" in Swagger UI hits
 * the real public URL {@code http://localhost:8080/api/payments}. This is the edge/BFF cost made
 * concrete — one client-facing contract stitched from a service that only knows its own slice.
 *
 * <p>The gateway base is externalised ({@code openapi.public-url}) so Compose / a real deploy can point
 * it at the actual public host without a rebuild; it defaults to the local gateway. It's injected on the
 * {@code @Bean} method (not a field) to keep with the project's no-field-injection convention.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI paymentOpenApi(@Value("${openapi.public-url:http://localhost:8080}") String publicUrl) {
        Server gateway = new Server()
                .url(publicUrl + "/api")
                .description("Public gateway (adds the /api namespace, strips it before the service)");

        return new OpenAPI()
                .info(new Info()
                        .title("ScreenMaster — Payment API")
                        .version("v1")
                        .description("""
                                Hosted-checkout payments behind one gateway port. POST /payments opens \
                                (or reuses) a checkout session with a gateway; the outcome arrives \
                                out-of-band on POST /payments/webhooks/{gateway} — a signed gateway \
                                webhook is the only path to PAID — and travels onward to Booking as \
                                PaymentSucceeded / PaymentFailed events. The sandbox gateway also serves \
                                its own pay page at /payments/sandbox/checkout/{sessionId}.""")
                        .contact(new Contact().name("ScreenMaster").email("doublem.shared@gmail.com")))
                .servers(List.of(gateway));
    }
}
