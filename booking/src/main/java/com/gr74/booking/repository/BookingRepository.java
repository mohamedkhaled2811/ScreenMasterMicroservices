package com.gr74.booking.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;

/**
 * Spring Data repository for {@link Booking}.
 */
public interface BookingRepository extends JpaRepository<Booking, Long>, JpaSpecificationExecutor<Booking> {

    /** Page a user's bookings. */
    Page<Booking> findByUserId(String userId, Pageable pageable);

    /** Pre-check before deleting a showtime: reject the delete if any booking references it. */
    boolean existsByShowtimeId(Long showtimeId);

    /**
     * Of the given seat ids, return those already held by an active ({@code PENDING}/{@code CONFIRMED})
     * booking for this showtime. Empty means all requested seats are free.
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

    /**
     * Confirm via one conditional UPDATE (never read-then-write): the race with expiry is decided by
     * the database. Returns 1 when a live PENDING row was confirmed, 0 otherwise.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
           update Booking b
              set b.status = :confirmed, b.paymentStatus = :paid
            where b.id = :id and b.status = :pending and b.expiresAt > :now
           """)
    int confirmIfStillPending(@Param("id") Long id, @Param("now") Instant now,
            @Param("pending") BookingStatus pending, @Param("confirmed") BookingStatus confirmed,
            @Param("paid") PaymentStatus paid);

    /**
     * Expire a still-PENDING hold via one conditional UPDATE. Flipping the status frees the seats;
     * kept rows serve as the audit trail. Returns 1 when a live row was expired, 0 otherwise.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
           update Booking b
              set b.status = :expired
            where b.id = :id and b.status = :pending
           """)
    int expireIfStillPending(@Param("id") Long id,
            @Param("pending") BookingStatus pending, @Param("expired") BookingStatus expired);

    /**
     * Mirror a payment failure onto the display-only field. Guarded to still-PENDING so a late
     * failure never overwrites a PAID. Returns 1 when the mirror landed, 0 otherwise.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
           update Booking b
              set b.paymentStatus = :failed
            where b.id = :id and b.status = :pending
           """)
    int mirrorPaymentFailure(@Param("id") Long id,
            @Param("pending") BookingStatus pending, @Param("failed") PaymentStatus failed);

    /** The expiry sweeper's input: holds of one status whose deadline has passed. */
    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant before);
}
