package com.gr74.catalog.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.catalog.model.SyncStatus;
import com.gr74.catalog.model.SyncType;

/**
 * Spring Data repository for {@link SyncStatus} bookkeeping. The sync looks up (or creates) the row
 * for a given {@link SyncType} at the start of each tick to resume the walk.
 */
public interface SyncStatusRepository extends JpaRepository<SyncStatus, Long> {

    Optional<SyncStatus> findBySyncType(SyncType syncType);
}
