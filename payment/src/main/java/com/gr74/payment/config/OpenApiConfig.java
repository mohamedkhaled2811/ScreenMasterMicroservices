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
 * OpenAPI title, version, and gateway server URL for the payment service.
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
