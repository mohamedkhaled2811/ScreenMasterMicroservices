package com.gr74.booking.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.Screen;

/**
 * Spring Data repository for {@link Screen}.
 *
 * <p>The queries traverse the {@code theater} association by its id ({@code TheaterId} in the derived
 * name resolves to {@code screen.theater.id}), so listing "screens of a theater" and checking
 * name-within-theater uniqueness never load the {@link com.gr74.booking.model.Theater} entity itself —
 * important under {@code open-in-view: false}. {@code existsByTheaterIdAndName} pre-checks the
 * {@code uq_screens_name_theater} constraint before insert.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the paged, filtered {@code GET /theaters/{id}/screens}
 * can run a composed {@link org.springframework.data.jpa.domain.Specification}. The {@code ScreenResponse}
 * mapper reads only {@code theater.getId()} — reading a lazy {@code @ManyToOne}'s id does <em>not</em>
 * initialize the proxy (Hibernate already holds the FK), so no {@code EntityGraph} fetch is needed even
 * under {@code open-in-view: false}. See {@code docs/concepts/pagination-and-filtering.md}.
 */
public interface ScreenRepository extends JpaRepository<Screen, Long>, JpaSpecificationExecutor<Screen> {

    List<Screen> findByTheaterId(Long theaterId);

    boolean existsByTheaterIdAndName(Long theaterId, String name);
}
