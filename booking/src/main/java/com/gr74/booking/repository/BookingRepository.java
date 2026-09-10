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
     * Used before deleting a showtime: if any booking row still references it, the delete must be
     * rejected. The FK is the real guard; this is the friendly pre-check.
     */
    boolean existsByShowtimeId(Long showtimeId);

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

    /**
     * The saga's confirm — <b>one conditional UPDATE, never read-then-write</b> (BUILD_PLAN 3.3).
     *
     * <p>The race between this and the expiry sweeper is decided by the database, not by code
     * order: a payment landing at 20:15:59.9 matches all three guards and flips the row, while the
     * sweeper ticking at 20:16 finds no PENDING row and moves nothing. Enum values ride as
     * parameters so the JPQL stays portable (runs on the H2 {@code @DataJpaTest} suite).
     *
     * @return 1 when the booking was still PENDING with a live hold (confirmed now), 0 when it was
     *         already CONFIRMED/EXPIRED/CANCELLED or the hold had lapsed — the caller re-reads to
     *         tell those apart
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
     * The sweeper's (and the confirmer's late-payment path's) expiry — same
     * {@code status = PENDING} guard as the confirm, so a concurrent confirm wins: whichever
     * statement matches first flips the row out of PENDING and the other updates zero rows.
     *
     * <p>Flipping the status <em>is</em> freeing the seats — {@code findSeatIdsHeldForShowtime}
     * only counts PENDING/CONFIRMED, so EXPIRED releases them to every future booking check. The
     * {@code booking_seats} rows are deliberately kept as the audit trail.
     *
     * @return 1 when a live PENDING row was expired, 0 when it was already gone
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
     * Mirror a gateway failure onto the read-model field — display only. Guarded to still-PENDING
     * so a late failure can never overwrite a PAID (the out-of-order case: FAILED arriving after
     * SUCCEEDED is dropped here, matching Payment's own terminal-state guard).
     *
     * @return 1 when the mirror landed, 0 when the booking had already left PENDING
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
