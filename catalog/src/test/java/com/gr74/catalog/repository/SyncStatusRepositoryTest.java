package com.gr74.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.gr74.catalog.model.SyncState;
import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;

/** Persistence slice for {@link SyncStatus} on H2. */
@DataJpaTest
class SyncStatusRepositoryTest {

    @Autowired
    private SyncStatusRepository repository;

    @Test
    void persistsAndFindsBySyncType() {
        SyncStatus status = new SyncStatus(SyncType.POPULAR);
        status.recordPageSynced(3, 500);
        repository.saveAndFlush(status);

        SyncStatus found = repository.findBySyncType(SyncType.POPULAR).orElseThrow();
        assertThat(found.getSyncType()).isEqualTo(SyncType.POPULAR);
        assertThat(found.getLastPage()).isEqualTo(3);
        assertThat(found.getTotalPages()).isEqualTo(500);
        assertThat(found.getState()).isEqualTo(SyncState.RUNNING);
        assertThat(found.getLastSyncedAt()).isNotNull();
    }

    @Test
    void recordsFailureWithMessageAndResumesFromLastPage() {
        SyncStatus status = new SyncStatus(SyncType.TOP_RATED);
        status.recordPageSynced(2, 100);
        status.markFailed("TMDB 503 on page 3");
        repository.saveAndFlush(status);

        SyncStatus found = repository.findBySyncType(SyncType.TOP_RATED).orElseThrow();
        assertThat(found.getState()).isEqualTo(SyncState.FAILED);
        assertThat(found.getErrorMessage()).contains("503");
        assertThat(found.getLastPage()).isEqualTo(2); // resume point preserved
        assertThat(found.isComplete()).isFalse();
    }

    @Test
    void completionIsDetected() {
        SyncStatus status = new SyncStatus(SyncType.NOW_PLAYING);
        status.recordPageSynced(50, 50);
        status.markCompleted();
        repository.saveAndFlush(status);

        SyncStatus found = repository.findBySyncType(SyncType.NOW_PLAYING).orElseThrow();
        assertThat(found.getState()).isEqualTo(SyncState.COMPLETED);
        assertThat(found.isComplete()).isTrue();
    }

    @Test
    void syncTypeIsUnique() {
        repository.saveAndFlush(new SyncStatus(SyncType.POPULAR));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        repository.saveAndFlush(new SyncStatus(SyncType.POPULAR)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
