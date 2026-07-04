package com.gr74.booking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gr74.booking.model.Screen;

/**
 * Spring Data repository for {@link Screen}.
 *
 * <p>The queries traverse the {@code theater} association by its id ({@code TheaterId} in the derived
 * name resolves to {@code screen.theater.id}), so listing "screens of a theater" and checking
 * name-within-theater uniqueness never load the {@link com.gr74.booking.model.Theater} entity itself —
 * important under {@code open-in-view: false}. {@code existsByTheaterIdAndName} pre-checks the
 * {@code uq_screens_name_theater} constraint before insert.
 */
public interface ScreenRepository extends JpaRepository<Screen, Long> {

    List<Screen> findByTheaterId(Long theaterId);

    boolean existsByTheaterIdAndName(Long theaterId, String name);
}
