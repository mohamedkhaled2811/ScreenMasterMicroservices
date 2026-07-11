package com.gr74.booking.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller-method parameter that should be bound to the id of the authenticated user.
 *
 * <p>A parameter annotated with {@code @CurrentUser} is resolved by {@link CurrentUserArgumentResolver}
 * — it never comes from the request body or the URL path. That's deliberate: <em>who you are</em> is an
 * ambient property of the request, not something the caller states in the payload (a caller must not be
 * able to book "as" another user by changing a field). Keeping identity out of the URL/body is also what
 * makes the Phase-7 swap to a real JWT a no-op on every controller.
 *
 * <p><b>Today (pre-Keycloak):</b> the resolver reads the {@code X-User-Id} request header. <b>In Phase 7:</b>
 * the resolver reads the JWT {@code sub} claim instead — the annotation, the controller signatures, the
 * services, and the {@code bookings.user_id} column are all unchanged. See
 * {@code docs/concepts/current-user-resolution.md}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
