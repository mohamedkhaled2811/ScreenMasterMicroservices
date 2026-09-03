package com.gr74.payment.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Registers a gateway adapter only when its credential property holds a <b>non-blank</b> value.
 *
 * <p>This exists because Spring's own {@code @ConditionalOnProperty} counts an <em>empty string</em>
 * as present. Our config supplies empty defaults ({@code ${STRIPE_SECRET_KEY:}}) so the service starts
 * without every gateway configured — and Compose passes empty strings for unset variables too — so
 * plain {@code @ConditionalOnProperty} would happily register a gateway with no credentials. That
 * adapter would then appear in {@code GET /payments/gateways}, be selectable by a client, and fail
 * only at the moment a real user tried to pay.
 *
 * <p>The rule this enforces is the one the plan states: <b>a misconfigured gateway is absent, not
 * broken.</b>
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
