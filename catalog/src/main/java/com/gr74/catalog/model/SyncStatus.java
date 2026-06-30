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
 * Bookkeeping for the resumable TMDB sync — one row per {@link SyncType}. The sync reads this to know
 * where it left off ({@link #lastPage}) and how far there is to go ({@link #totalPages}); a crash or
 * rate-limit pause therefore resumes the walk rather than restarting it.
 *
 * <p>Unlike {@link Movie}/{@link Genre}, the {@code id} here is <em>generated</em> (an internal
 * surrogate) — there's no external id to assign. {@code syncType} and {@code state} are
 * {@code @Enumerated(STRING)}: the enum <em>name</em> is persisted so reordering constants can't
 * corrupt rows (the fragile-ordinal trap the schema doc flags in the monolith).
 *
 * <p>State transitions go through intention-revealing methods ({@link #markRunning()},
 * {@link #recordPageSynced(int, int)}, {@link #markCompleted()}, {@link #markFailed(String)}) rather
 * than raw setters, so the valid lifecycle lives in one place. Schema owned by Liquibase
 * (changeset 002); this entity must match it under {@code ddl-auto=validate}.
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
     * The incremental-refresh cursor (UTC day): the catalog is fresh with respect to TMDB's change
     * feed <em>through</em> this date. Only meaningful on the {@link SyncType#CHANGES} row; null until
     * the first refresh runs, after which each refreshed day advances it. The page-walk fields above
     * are unused on that row.
     */
    @Column(name = "last_changes_synced_date")
    private LocalDate lastChangesSyncedDate;

    /** Start a fresh bookkeeping row for a type (nothing synced yet, RUNNING). */
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

    /** Record that a page was fully synced — advance the cursor and learn the total page count. */
    public void recordPageSynced(int page, int totalPages) {
        this.lastPage = page;
        this.totalPages = totalPages;
        this.lastSyncedAt = Instant.now();
    }

    /** The walk reached the final page: the catalog is fresh for this type. */
    public void markCompleted() {
        this.state = SyncState.COMPLETED;
        this.errorMessage = null;
        this.lastSyncedAt = Instant.now();
    }

    /** The walk errored: record why so the next tick resumes from {@link #lastPage}. */
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
     * Advance the incremental-refresh cursor to a freshly-refreshed UTC day (see
     * {@link #lastChangesSyncedDate}). Touches {@code lastSyncedAt} too so the row reflects the most
     * recent refresh activity. Used only on the {@link SyncType#CHANGES} row.
     */
    public void recordChangesSynced(LocalDate through) {
        this.lastChangesSyncedDate = through;
        this.lastSyncedAt = Instant.now();
    }
}
