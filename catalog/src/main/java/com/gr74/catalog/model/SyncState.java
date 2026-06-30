package com.gr74.catalog.model;

/**
 * Lifecycle of a single sync run for a {@link SyncType}.
 *
 * <ul>
 *   <li>{@code RUNNING} — a tick is currently walking this list.</li>
 *   <li>{@code COMPLETED} — the last run reached the final page; the catalog is fresh for this type.</li>
 *   <li>{@code FAILED} — the last run errored mid-walk; {@code error_message} says why and the next
 *       tick resumes from {@code last_page}.</li>
 * </ul>
 *
 * <p>Persisted as a {@code STRING} (never ordinal) — see {@link SyncType} for the why.
 */
public enum SyncState {
    RUNNING,
    COMPLETED,
    FAILED
}
