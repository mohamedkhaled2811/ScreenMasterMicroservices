package com.gr74.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Fake payment provider service.
 *
 * <p>Exposes {@code POST /payments}: it sleeps ~300ms and then approves, or declines at a
 * configurable {@code FAIL_RATE}, simulating a real provider whose failures we fully control
 * (used by the saga in Phase 3 and the circuit breaker in Phase 5). The fake behaviour lives
 * behind a {@link com.gr74.payment.provider.PaymentProvider} so a real provider becomes an
 * add-only {@code @Profile("real")} sibling — see {@code docs/concepts/spring-boot-annotations.md}.
 *
 * <p>Unlike the Eureka <i>server</i>, this is a Eureka <b>client</b>: just having
 * {@code spring-cloud-starter-netflix-eureka-client} on the classpath auto-registers it on
 * startup — no {@code @EnableEurekaClient} annotation is needed (it's a deprecated no-op in
 * modern Spring Cloud). See {@code docs/concepts/service-discovery.md}.
 *
 * <p>{@code @ConfigurationPropertiesScan} picks up {@link com.gr74.payment.config.PaymentProps} so
 * {@code FAIL_RATE} and the latency are bound from config rather than hard-coded.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
