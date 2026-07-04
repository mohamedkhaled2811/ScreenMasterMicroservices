package com.gr74.booking.controller.dto;

/**
 * The query-parameter surface of {@code GET /theaters} — Spring binds matching request params onto
 * these record components by name (e.g. {@code ?name=imax&location=cairo}). Every field is
 * <em>optional</em>: a null/blank value contributes no predicate, which is exactly what "filter by
 * whatever the caller sends" means. The non-null fields become composable
 * {@link com.gr74.booking.repository.spec.TheaterSpecifications} fragments combined with {@code AND}.
 *
 * <p>Mirrors catalog's {@code MovieFilter}: a binding DTO, not a wire-out type. Paging and sorting are
 * <em>not</em> here — they ride on Spring Data's {@code Pageable} ({@code page}/{@code size}/{@code sort}),
 * bound separately. A {@code record} has no no-arg constructor, so Spring binds it via its canonical
 * constructor; absent params arrive as {@code null}, so "absent" and "present" stay distinguishable.
 */
public record TheaterFilter(

        /** Case-insensitive substring match on {@code name}. */
        String name,

        /** Case-insensitive substring match on {@code location}. */
        String location) {

}
