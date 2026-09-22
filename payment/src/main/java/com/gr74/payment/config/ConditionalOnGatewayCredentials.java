package com.gr74.payment.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Registers a gateway adapter only when its credential property is present and non-blank.
 *
 * @see GatewayCredentialsCondition
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(GatewayCredentialsCondition.class)
public @interface ConditionalOnGatewayCredentials {

    /** Fully-qualified property that must be present and non-blank, e.g. {@code payment.gateway.stripe.secret-key}. */
    String value();
}
