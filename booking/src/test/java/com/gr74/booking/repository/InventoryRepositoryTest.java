package com.gr74.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.gr74.booking.config.JpaAuditingConfig;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;
import com.gr74.booking.model.Seat;
import com.gr74.booking.model.SeatType;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.model.Theater;

/**
 * Persistence slice for the inventory + showtime schema on in-memory H2 (Liquibase off, Hibernate
 * builds the test schema from the entities). Proves the things the write endpoints and the Phase-3
 * saga depend on: the theater→screen→seat→seat-type graph round-trips through real FKs; the seats
 * repository eager-fetches {@code seatType} (so the DTO mapper can read the type name under
 * {@code open-in-view: false}); and every uniqueness invariant actually rejects a duplicate.
 *
 * <p>{@link JpaAuditingConfig} is imported so {@code @CreatedDate} populates the NOT NULL
 * {@code created_date} — {@code @DataJpaTest} doesn't load {@code @Configuration} beans automatically.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class InventoryRepositoryTest {

    private static final String DOWNTOWN_IMAX = "Downtown IMAX";
    private static final String SCREEN_1 = "Screen 1";
    private static final String SCREEN_S1 = "S1";
    private static final String STANDARD = "STANDARD";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TheaterRepository theaterRepository;

    @Autowired
    private ScreenRepository screenRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ShowtimeRepository showtimeRepository;

    @Test
    void persistsTheaterScreenSeatGraphAndFetchesSeatTypeEagerly() {
        Theater theater = em.persist(new Theater(DOWNTOWN_IMAX, "Main St"));
        SeatType standard = em.persist(new SeatType(STANDARD, new BigDecimal("1.00")));
        Screen screen = em.persist(new Screen(SCREEN_1, ScreenType.SCREEN_3D, theater));
        em.persist(new Seat("A", 1, screen, standard));
        em.flush();
        em.clear(); // force a fresh load so the @EntityGraph fetch is actually exercised

        List<Seat> seats = seatRepository.findByScreenIdOrderBySeatRowAscSeatNumberAsc(screen.getId());

        assertThat(seats).hasSize(1);
        Seat loaded = seats.get(0);
        assertThat(loaded.getSeatRow()).isEqualTo("A");
        assertThat(loaded.getSeatNumber()).isEqualTo(1);
        // seatType is eager-fetched by the repository's @EntityGraph — readable after em.clear().
        assertThat(loaded.getSeatType().getName()).isEqualTo(STANDARD);
        assertThat(theater.getCreatedDate()).isNotNull(); // auditing populated it
    }

    @Test
    void theaterNameIsUnique() {
        em.persist(new Theater(DOWNTOWN_IMAX, "Main St"));
        em.flush();

        assertThatThrownBy(() -> {
            theaterRepository.save(new Theater(DOWNTOWN_IMAX, "Elsewhere"));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void screenNameIsUniqueWithinTheaterButNotAcross() {
        Theater a = em.persist(new Theater("Theater A", null));
        Theater b = em.persist(new Theater("Theater B", null));
        em.persist(new Screen(SCREEN_1, ScreenType.FRONT_SCREEN, a));
        em.flush();

        // Same name in a DIFFERENT theater is allowed.
        Screen inB = screenRepository.save(new Screen(SCREEN_1, ScreenType.FRONT_SCREEN, b));
        em.flush();
        assertThat(inB.getId()).isNotNull();

        // Same name in the SAME theater is rejected.
        assertThatThrownBy(() -> {
            screenRepository.save(new Screen(SCREEN_1, ScreenType.FRONT_SCREEN, a));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void seatPositionIsUniqueWithinScreen() {
        Theater theater = em.persist(new Theater("T", null));
        SeatType type = em.persist(new SeatType(STANDARD, new BigDecimal("1.00")));
        Screen screen = em.persist(new Screen(SCREEN_S1, ScreenType.FRONT_SCREEN, theater));
        em.persist(new Seat("A", 1, screen, type));
        em.flush();

        assertThatThrownBy(() -> {
            seatRepository.save(new Seat("A", 1, screen, type));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void showtimeSlotIsUniqueAndStoresMovieIdWithoutAnFk() {
        Theater theater = em.persist(new Theater("T", null));
        Screen screen = em.persist(new Screen(SCREEN_S1, ScreenType.FRONT_SCREEN, theater));
        LocalDate date = LocalDate.of(2026, 7, 10);
        LocalTime time = LocalTime.of(19, 30);
        // movieId 999999 need not exist anywhere — the cut means there is no FK to satisfy.
        em.persist(new Showtime(999_999L, screen, date, time, new BigDecimal("12.50")));
        em.flush();

        // Same (screen, movie, date, time) is a duplicate slot.
        assertThatThrownBy(() -> {
            showtimeRepository.save(new Showtime(999_999L, screen, date, time, new BigDecimal("15.00")));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsUpcomingShowtimesByMovieFromDate() {
        Theater theater = em.persist(new Theater("T", null));
        Screen screen = em.persist(new Screen(SCREEN_S1, ScreenType.FRONT_SCREEN, theater));
        long movieId = 550L;
        em.persist(new Showtime(movieId, screen, LocalDate.of(2026, 7, 1), LocalTime.of(18, 0), new BigDecimal("10.00")));
        em.persist(new Showtime(movieId, screen, LocalDate.of(2026, 7, 20), LocalTime.of(20, 0), new BigDecimal("10.00")));
        em.flush();
        em.clear();

        List<Showtime> upcoming = showtimeRepository
                .findByMovieIdAndShowDateGreaterThanEqualOrderByShowDateAscShowTimeAsc(
                        movieId, LocalDate.of(2026, 7, 10));

        assertThat(upcoming).hasSize(1);
        assertThat(upcoming.get(0).getShowDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(upcoming.get(0).getMovieId()).isEqualTo(movieId);
    }
}
