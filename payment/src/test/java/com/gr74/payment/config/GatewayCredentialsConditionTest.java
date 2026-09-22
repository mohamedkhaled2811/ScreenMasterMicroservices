package com.gr74.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gr74.payment.gateway.stripe.StripeGateway;

/**
 * A gateway without credentials stays unregistered, never listed-but-broken.
 */
class GatewayCredentialsConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(StripeGatewayProps.class, () -> new StripeGatewayProps(
                    "sk_test_x", "whsec_x", null, null, null, 300, false));

    @Configuration
    static class TestConfig {
        /** The adapters need these collaborators; bare instances suffice since no call is made. */
        @Bean
        RestClient.Builder gatewayRestClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    @DisplayName("an unset credential leaves the gateway unregistered")
    void absentPropertyMeansNoBean() {
        runner.withUserConfiguration(StripeGateway.class)
                .run(context -> assertThat(context).doesNotHaveBean(StripeGateway.class));
    }

    @Test
    @DisplayName("an EMPTY credential also leaves it unregistered — the actual bug")
    void blankPropertyMeansNoBean() {
        // This is the case @ConditionalOnProperty got wrong: `STRIPE_SECRET_KEY=` in a .env file, or
        // Compose passing through an unset variable, both arrive as an empty string.
        runner.withPropertyValues("payment.gateway.stripe.secret-key=")
                .withUserConfiguration(StripeGateway.class)
                .run(context -> assertThat(context).doesNotHaveBean(StripeGateway.class));
    }

    @Test
    @DisplayName("whitespace is not a credential either")
    void whitespaceOnlyMeansNoBean() {
        runner.withPropertyValues("payment.gateway.stripe.secret-key=   ")
                .withUserConfiguration(StripeGateway.class)
                .run(context -> assertThat(context).doesNotHaveBean(StripeGateway.class));
    }

    @Test
    @DisplayName("a real credential registers the gateway")
    void realCredentialRegistersBean() {
        runner.withPropertyValues("payment.gateway.stripe.secret-key=sk_test_abc123")
                .withUserConfiguration(StripeGateway.class)
                .run(context -> assertThat(context).hasSingleBean(StripeGateway.class));
    }
}
