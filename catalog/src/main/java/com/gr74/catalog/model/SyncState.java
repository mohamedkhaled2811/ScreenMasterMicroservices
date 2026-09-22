package com.gr74.catalog.model;

/**
 * Lifecycle of a sync run for a {@link SyncType}.
 */
public enum SyncState {
    RUNNING,
    COMPLETED,
    FAILED
}
