package com.gr74.booking.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.repository.BookingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Flips every lapsed PENDING booking to EXPIRED. Seats are freed by the status flip itself;
 * the flip is conditional on still-PENDING so a concurrent confirm wins the race at the database.
 * Separate bean from the sweeper so the transactional proxy applies.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingExpirer {

    private final BookingRepository bookings;

    /** Expire every PENDING booking past {@code now}; returns how many holds were flipped. */
    @Transactional
    public int expireDueBookings(Instant now) {
        List<Booking> lapsed = bookings.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, now);
        int expired = 0;
        for (Booking booking : lapsed) {
            int rows = bookings.expireIfStillPending(
                    booking.getId(), BookingStatus.PENDING, BookingStatus.EXPIRED);
            if (rows == 1) {
                expired++;
                log.info("Expired booking id={} ref={} (hold lapsed) — seats released, rows kept",
                        booking.getId(), booking.getBookingReference());
            }
        }
        return expired;
    }
}
