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
 * Spring Data repository for {@link Movie}. The key is {@link Long} — the assigned TMDB movie id.
 *
 * <p>{@link #findById(Long id)} is overridden only to attach an {@link EntityGraph} so the
 * {@code genres} collection is fetched in the <em>same</em> query (a join), not lazily. We run with
 * {@code open-in-view: false}, so a lazy {@code genres} would otherwise throw
 * {@code LazyInitializationException} when the DTO mapper reads it after the transaction closes —
 * and an {@code EntityGraph} also avoids the N+1 a per-row lazy load would cause.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the dynamic {@code GET /movies} filter can run a
 * composed {@link org.springframework.data.jpa.domain.Specification} with paging/sorting. See
 * {@link #findMoviePage(org.springframework.data.jpa.domain.Specification, Pageable)} for why the
 * page is fetched in two steps rather than with a naive {@code EntityGraph} on {@code findAll}.
 */
public interface MovieRepository extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

    @Override
    @EntityGraph(attributePaths = "genres")
    Optional<Movie> findById(Long id);

    /**
     * Load a batch of movies <em>with</em> their genres in one query, preserving the given id order.
     *
     * <p>Why this exists: paginating a to-many fetch in a single query forces Hibernate to apply
     * {@code LIMIT}/{@code OFFSET} in memory (the dreaded "firstResult/maxResults specified with
     * collection fetch; applying in memory" warning) because each movie spans multiple genre rows.
     * The fix is the standard two-query pattern — first page the movie <em>ids</em> (no collection
     * fetch, so {@code LIMIT} is safe in SQL), then fetch exactly those ids here with a join. The
     * {@code @EntityGraph} makes the join a fetch so the {@code genres} are loaded inside the
     * transaction (we run {@code open-in-view: false}) with no N+1.
     */
    @EntityGraph(attributePaths = "genres")
    List<Movie> findWithGenresByIdIn(@Param("ids") List<Long> ids);

    /**
     * Of the given candidate ids, return only those we actually store. The incremental refresh feeds
     * TMDB's change feed (potentially thousands of edited ids across all of TMDB) through this to keep
     * only the movies in <em>our</em> catalog — so a refresh tick only ever issues detail fetches for
     * movies we own, never TMDB's full firehose. A projection on the id alone: no entities are loaded.
     */
    @Query("select m.id from Movie m where m.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    default Page<Movie> findMoviePage(
            Specification<Movie> spec, Pageable pageable) {
        // 1) Page the ids only — collection-free, so SQL LIMIT/OFFSET is correct and cheap.
        Page<Long> idPage = findAll(spec, pageable).map(Movie::getId);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        // 2) Re-fetch the page's movies WITH genres, then restore the page's sort order (the IN
        //    query doesn't preserve it) so the response matches the requested sort.
        List<Movie> withGenres = findWithGenresByIdIn(idPage.getContent());
        List<Movie> ordered = idPage.getContent().stream()
                .map(id -> withGenres.stream().filter(m -> m.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }
}
