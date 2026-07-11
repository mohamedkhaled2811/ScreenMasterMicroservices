package com.gr74.booking.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;

/**
 * Spring Data repository for {@link Booking}.
 *
 * <p>{@link #findByUserId} backs the "my bookings" read (the M2 composition): it pages a user's own
 * bookings; the service then resolves each row's snapshotted {@code movieId} to a title via Catalog.
 * {@link #findSeatIdsHeldForShowtime} is the <em>local</em> double-booking guard — the one invariant
 * that must stay strongly consistent within Booking (you can't sell the same seat twice), enforced in
 * application code plus the {@code uq_booking_seats_booking_seat} unique constraint as the backstop.
 */
public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    /** Page a user's bookings. Identity comes from the resolver ({@code @CurrentUser}), never the URL. */
    Page<Booking> findByUserId(String userId, Pageable pageable);

    /**
     * Of the given seat ids, which are already held by an <em>active</em> booking for this showtime?
     * "Active" = a status that still reserves the seat ({@code PENDING} or {@code CONFIRMED}); a
     * {@code CANCELLED}/{@code EXPIRED} booking has released its seats and must not block a rebook. An
     * empty result means all requested seats are free. This is a read-side pre-check for a friendly 409;
     * the unique constraint on {@code (booking_id, seat_id)} plus the Phase-3 seat-hold logic are the
     * hard backstop against a race.
     */
    @Query("""
            select bs.seatId
            from BookingSeat bs
            where bs.booking.showtimeId = :showtimeId
              and bs.booking.status in :activeStatuses
              and bs.seatId in :seatIds
            """)
    List<Long> findSeatIdsHeldForShowtime(
            @Param("showtimeId") Long showtimeId,
            @Param("seatIds") Collection<Long> seatIds,
            @Param("activeStatuses") Collection<BookingStatus> activeStatuses);
}
