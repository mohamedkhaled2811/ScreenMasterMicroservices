package com.gr74.booking.model;

/**
 * The projection technology of a screen.
 *
 * <p>Persisted as a {@code String} (never an ordinal — see the project convention and
 * {@code docs/concepts/jpa-and-hibernate.md}). The monolith stored this enum as an <em>ordinal</em>
 * (schema doc §2.4/§9 flags it as fragile): reordering the constants there would silently remap every
 * existing row. We store the name instead, so the value is a stable part of the schema contract.
 */
public enum ScreenType {
    FRONT_SCREEN,
    REAR_PROJECTION,
    SCREEN_3D
}
