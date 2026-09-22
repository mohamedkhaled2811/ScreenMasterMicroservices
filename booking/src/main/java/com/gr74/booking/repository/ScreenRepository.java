package com.gr74.booking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.Screen;

/**
 * Spring Data repository for {@link Screen}. Derived queries traverse the {@code theater} association
 * by id, so they never load the theater entity.
 */
public interface ScreenRepository extends JpaRepository<Screen, Long>, JpaSpecificationExecutor<Screen> {

    List<Screen> findByTheaterId(Long theaterId);

    boolean existsByTheaterIdAndName(Long theaterId, String name);
}
