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
 * <p><b>Two title-resolution strategies, chosen per request</b> ({@code ?source=}) so both are demoable
 * side-by-side (BUILD_PLAN 2.4). The bookings query and the {@link MyBookingDto} shape are identical; only
 * where the title comes from differs:
 * <ul>
 *   <li>{@link TitleSource#COMPOSITION} (way A) — resolve titles live from Catalog in one batch call
 *       ({@link CatalogClient#titlesByIds}). <b>Degrades:</b> if Catalog is down it returns no titles, so
 *       the list still answers with {@code movieTitle: null} instead of a 503. That partial-failure trade
 *       is the cost this path exists to make you feel.</li>
 *   <li>{@link TitleSource#READMODEL} (way B) — resolve titles from Booking's local {@code movie_titles}
 *       read model ({@link MovieTitleReadModel}), lazy-backfilling a cache miss. A Catalog outage doesn't
 *       stop already-cached titles from rendering — the resilience way A trades away — at the price of
 *       eventual consistency (a rename lags until the event lands).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MyBookingsService {

    private final BookingRepository bookingRepository;
    private final CatalogClient catalogClient;
    private final MovieTitleReadModel movieTitleReadModel;

    @Transactional(readOnly = true)
    public Page<MyBookingDto> myBookings(String userId, Pageable pageable, TitleSource source) {
        Page<Booking> bookings = bookingRepository.findByUserId(userId, pageable);
        Set<Long> movieIds = bookings.stream().map(Booking::getMovieId).collect(Collectors.toSet());

        Map<Long, String> titles = resolveTitles(source, movieIds);
        log.info("My bookings for userId={} via {}: {} row(s), resolved {}/{} title(s)",
                userId, source, bookings.getNumberOfElements(), titles.size(), movieIds.size());

        return bookings.map(b -> MyBookingDto.of(b, titles.get(b.getMovieId())));
    }

    /** Way A resolves live from Catalog (one batch call); way B resolves from the local read model. */
    private Map<Long, String> resolveTitles(TitleSource source, Set<Long> movieIds) {
        return switch (source) {
            case COMPOSITION -> catalogClient.titlesByIds(movieIds);
            case READMODEL -> movieTitleReadModel.titlesByIds(movieIds);
        };
    }
}
