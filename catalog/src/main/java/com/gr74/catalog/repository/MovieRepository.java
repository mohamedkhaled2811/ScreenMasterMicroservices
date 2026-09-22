package com.gr74.catalog.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.catalog.model.Movie;

/**
 * Spring Data repository for {@link Movie}.
 */
public interface MovieRepository extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

    @Override
    @EntityGraph(attributePaths = "genres")
    Optional<Movie> findById(Long id);

    /** Load a batch of movies with their genres, preserving id order. */
    @EntityGraph(attributePaths = "genres")
    List<Movie> findWithGenresByIdIn(@Param("ids") List<Long> ids);

    /** Of the given ids, return only those stored. Used to filter the change feed. */
    @Query("select m.id from Movie m where m.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    default Page<Movie> findMoviePage(
            Specification<Movie> spec, Pageable pageable) {
        // 1) Page ids only, then 2) re-fetch with genres and restore order.
        Page<Long> idPage = findAll(spec, pageable).map(Movie::getId);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        // 2) Re-fetch with genres; the IN query doesn't preserve order.
        List<Movie> withGenres = findWithGenresByIdIn(idPage.getContent());
        List<Movie> ordered = idPage.getContent().stream()
                .map(id -> withGenres.stream().filter(m -> m.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }
}
