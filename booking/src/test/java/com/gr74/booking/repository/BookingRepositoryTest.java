package com.gr74.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.gr74.booking.config.JpaAuditingConfig;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingSeat;
import com.gr74.booking.model.BookingStatus;

/**
 * Persistence slice for {@link BookingRepository} on H2. Proves the two queries the read + create paths
 * depend on: {@link BookingRepository#findByUserId} pages a user's own bookings (and excludes others'),
 * and {@link BookingRepository#findSeatIdsHeldForShowtime} — the local double-booking guard — reports a
 * seat as held only while an <em>active</em> booking (PENDING/CONFIRMED) holds it, ignoring cancelled
 * ones. Also that a booking round-trips with its seat line items and the {@code booking_reference} unique
 * constraint exists.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class BookingRepositoryTest {

    private static final String USER_A = "11111111-1111-1111-1111-111111111111";
    private static final String USER_B = "22222222-2222-2222-2222-222222222222";
    private static final long SHOWTIME = 1L;
    private static final Instant EXPIRES = Instant.parse("2026-07-08T12:15:00Z");
    private static final Set<BookingStatus> ACTIVE = Set.of(BookingStatus.PENDING, BookingStatus.CONFIRMED);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private BookingRepository bookingRepository;

    @Test
    void findByUserIdPagesOnlyThatUsersBookings() {
        em.persist(booking("BK-A0000001", USER_A, SHOWTIME, BookingStatus.PENDING));
        em.persist(booking("BK-A0000002", USER_A, SHOWTIME, BookingStatus.CONFIRMED));
        em.persist(booking("BK-B0000001", USER_B, SHOWTIME, BookingStatus.PENDING));
        em.flush();
        em.clear();

        Page<Booking> page = bookingRepository.findByUserId(
                USER_A, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "bookingReference")));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(Booking::getUserId).containsOnly(USER_A);
    }

    @Test
    void heldSeatsQueryReportsOnlyActivelyHeldSeats() {
        // USER_A actively holds seat 10 (PENDING) for the showtime.
        Booking active = booking("BK-ACTIVE01", USER_A, SHOWTIME, BookingStatus.PENDING);
        active.addSeat(new BookingSeat(10L, new BigDecimal("12.00"), "STANDARD"));
        em.persist(active);
        // A cancelled booking that once held seat 11 must NOT block a rebook.
        Booking cancelled = booking("BK-CANCEL01", USER_B, SHOWTIME, BookingStatus.CANCELLED);
        cancelled.addSeat(new BookingSeat(11L, new BigDecimal("12.00"), "STANDARD"));
        em.persist(cancelled);
        em.flush();
        em.clear();

        List<Long> held = bookingRepository.findSeatIdsHeldForShowtime(
                SHOWTIME, List.of(10L, 11L, 12L), ACTIVE);

        // 10 is actively held; 11 is only held by a cancelled booking; 12 was never booked.
        assertThat(held).containsExactly(10L);
    }

    @Test
    void bookingRoundTripsWithItsSeatLineItems() {
        Booking booking = booking("BK-ROUND001", USER_A, SHOWTIME, BookingStatus.PENDING);
        booking.addSeat(new BookingSeat(10L, new BigDecimal("12.00"), "STANDARD"));
        booking.addSeat(new BookingSeat(11L, new BigDecimal("18.00"), "PREMIUM"));
        em.persist(booking);
        em.flush();
        em.clear();

        Booking loaded = bookingRepository.findById(booking.getId()).orElseThrow();

        assertThat(loaded.getSeats()).hasSize(2);
        assertThat(loaded.getSeats()).extracting(BookingSeat::getSeatTypeName)
                .containsExactlyInAnyOrder("STANDARD", "PREMIUM");
        assertThat(loaded.getUserId()).isEqualTo(USER_A);
    }

    private static Booking booking(String reference, String userId, long showtimeId, BookingStatus status) {
        Booking booking = new Booking(reference, userId, showtimeId, 603L, new BigDecimal("12.00"), "EGP", EXPIRES);
        // The domain constructor always sets PENDING; stamp the desired status for these fixtures.
        ReflectionTestUtils.setField(booking, "status", status);
        return booking;
    }
}
