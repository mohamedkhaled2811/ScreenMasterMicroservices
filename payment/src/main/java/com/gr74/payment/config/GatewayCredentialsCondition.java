package com.gr74.payment.config;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * The matcher behind {@link ConditionalOnGatewayCredentials}: the named property must exist
 * <em>and</em> be non-blank.
 *
 * <p>The blank check is the entire reason this class exists — see the annotation's javadoc.
 */
public class GatewayCredentialsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnGatewayCredentials.class.getName());
        if (attributes == null) {
            return false;
        }
        String property = (String) attributes.get("value");
        return StringUtils.hasText(context.getEnvironment().getProperty(property));
    }
}
