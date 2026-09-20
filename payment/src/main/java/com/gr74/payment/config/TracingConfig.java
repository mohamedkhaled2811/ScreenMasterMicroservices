package com.gr74.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

/**
 * Wires the aspect behind {@code @Observed}.
 *
 * <p>{@code @Observed} is implemented by {@link ObservedAspect}, a real AspectJ aspect — without the
 * {@code ObservedAspect} bean on the context the annotation is silently inert: the method runs, no
 * span is created, nothing logs a hint. Boot's {@code ObservationAutoConfiguration} defines one too
 * (conditional on the class + on a missing bean), so this is belt-and-braces — but explicit, so the
 * contract "any {@code @Observed} here produces an observation" is declared in the module that uses
 * it, not inherited by auto-configuration luck. {@code spring-boot-starter-aspectj} is already on
 * the classpath (the resilience4j starter pulls it).
 */
@Configuration
public class TracingConfig {

    @Bean
    public ObservedAspect observedAspect(ObservationRegistry registry) {
        return new ObservedAspect(registry);
    }
}