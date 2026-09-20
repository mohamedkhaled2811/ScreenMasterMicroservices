package com.gr74.payment.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code String} controller parameter as "the acting user's id, resolved from the verified
 * JWT's {@code sub} claim".
 *
 * <p>The mirror of Booking's {@code @CurrentUser} seam: controllers declare
 * {@code create(@CurrentUser String userId, …)} and stay oblivious to where the id comes from, so
 * identity flows through one resolver ({@link CurrentUserArgumentResolver}) instead of a
 * client-set header on every method. Who you are is an ambient property of the request — it must
 * never ride in the URL or the body, where a caller could book "as" someone else by changing a
 * field. See {@code docs/concepts/current-user-resolution.md}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
