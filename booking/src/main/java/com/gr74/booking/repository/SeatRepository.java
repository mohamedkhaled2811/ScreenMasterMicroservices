package com.gr74.booking.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.gr74.booking.model.Seat;

/**
 * Spring Data repository for {@link Seat}. The {@code EntityGraph} on {@code seatType} fetches the
 * type in one query, avoiding lazy-load failures after the transaction and per-seat N+1 loads.
 */
public interface SeatRepository extends JpaRepository<Seat, Long>, JpaSpecificationExecutor<Seat> {

    @EntityGraph(attributePaths = "seatType")
    List<Seat> findByScreenIdOrderBySeatRowAscSeatNumberAsc(Long screenId);

    boolean existsByScreenIdAndSeatRowAndSeatNumber(Long screenId, String seatRow, Integer seatNumber);

    /** Re-fetch a batch of seats with their {@code seatType} in one query. */
    @EntityGraph(attributePaths = "seatType")
    List<Seat> findWithSeatTypeByIdIn(List<Long> ids);

    /**
     * Page seats matching {@code spec} with each seat's {@code seatType} fetched: page the ids first
     * (collection-free, so SQL LIMIT/OFFSET is exact), then re-fetch that page and restore sort order.
     */
    default Page<Seat> findSeatPage(Specification<Seat> spec, Pageable pageable) {
        Page<Long> idPage = findAll(spec, pageable).map(Seat::getId);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Seat> withType = findWithSeatTypeByIdIn(idPage.getContent());
        List<Seat> ordered = idPage.getContent().stream()
                .map(id -> withType.stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow())
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }
}
