package com.gr74.catalog.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Bookkeeping for the resumable TMDB sync — one row per {@link SyncType}.
 */
@Entity
@Table(name = "sync_status")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA needs a no-arg ctor; nobody else should use it
public class SyncStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_type", nullable = false, unique = true)
    private SyncType syncType;

    @Column(name = "last_page", nullable = false)
    private int lastPage;

    @Column(name = "total_pages", nullable = false)
    private int totalPages;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncState state;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /**
     * Incremental-refresh cursor (UTC day). Only used on the {@link SyncType#CHANGES} row.
     */
    @Column(name = "last_changes_synced_date")
    private LocalDate lastChangesSyncedDate;

    /** Start a fresh bookkeeping row for a type. */
    public SyncStatus(SyncType syncType) {
        this.syncType = syncType;
        this.lastPage = 0;
        this.totalPages = 0;
        this.state = SyncState.RUNNING;
    }

    /** A new tick is starting: clear any prior error and mark RUNNING. */
    public void markRunning() {
        this.state = SyncState.RUNNING;
        this.errorMessage = null;
    }

    /** Record that a page was synced. */
    public void recordPageSynced(int page, int totalPages) {
        this.lastPage = page;
        this.totalPages = totalPages;
        this.lastSyncedAt = Instant.now();
    }

    /** The walk reached the final page. */
    public void markCompleted() {
        this.state = SyncState.COMPLETED;
        this.errorMessage = null;
        this.lastSyncedAt = Instant.now();
    }

    /** The walk errored: record why. */
    public void markFailed(String error) {
        this.state = SyncState.FAILED;
        this.errorMessage = error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 1000));
        this.lastSyncedAt = Instant.now();
    }

    /** True once we know the total and have synced every page. */
    public boolean isComplete() {
        return totalPages > 0 && lastPage >= totalPages;
    }

    /**
     * Advance the incremental-refresh cursor to a freshly-refreshed UTC day.
     */
    public void recordChangesSynced(LocalDate through) {
        this.lastChangesSyncedDate = through;
        this.lastSyncedAt = Instant.now();
    }
}
