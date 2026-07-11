package com.gr74.booking.service;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.dto.MyBookingDto;
import com.gr74.booking.model.Booking;
import com.gr74.booking.repository.BookingRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The M2 lesson made concrete: "show me my bookings, with the movie title for each."
 *
 * <p>The monolith did this with a single {@code JOIN booking → showtime → movie}. After decomposition
 * the title lives in another service's database, so this class <b>hand-writes that JOIN over the
 * network</b> (API composition — plan Way A):
 * <ol>
 *   <li>read the user's own bookings (paged) from booking-db;</li>
 *   <li>collect the page's <em>distinct</em> {@code movieId}s (snapshotted onto each booking at create);</li>
 *   <li>resolve them to titles in <b>one</b> Catalog call — {@link CatalogClient#titlesByIds} — not one
 *       call per row (the network N+1 naive composition falls into);</li>
 *   <li>merge title into each row, {@code null} on a miss.</li>
 * </ol>
 *
 * <p><b>Partial-failure behaviour (the cost you feel):</b> the client <em>degrades</em> — if Catalog is
 * down it returns no titles rather than throwing, so this list still answers with {@code movieTitle:
 * null} instead of a 503. That's the deliberate trade this endpoint exists to demonstrate, and the exact
 * contrast Part 3's CQRS read model removes (a local title copy wouldn't depend on Catalog at read time).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyBookingsService {

    private final BookingRepository bookingRepository;
    private final CatalogClient catalogClient;

    @Transactional(readOnly = true)
    public Page<MyBookingDto> myBookings(String userId, Pageable pageable) {
        Page<Booking> bookings = bookingRepository.findByUserId(userId, pageable);

        // One batch lookup for the whole page's distinct movie ids — the N+1 fix. Degrades to an empty
        // map if Catalog is down (see CatalogClient#titlesByIds), so a title miss is just a null below.
        Set<Long> movieIds = bookings.stream().map(Booking::getMovieId).collect(Collectors.toSet());
        Map<Long, String> titles = catalogClient.titlesByIds(movieIds);
        log.info("My bookings for userId={}: {} row(s), resolved {}/{} title(s) from Catalog",
                userId, bookings.getNumberOfElements(), titles.size(), movieIds.size());

        return bookings.map(b -> MyBookingDto.of(b, titles.get(b.getMovieId())));
    }
}
