package com.gr74.booking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.gr74.booking.config.JpaAuditingConfig;
import com.gr74.booking.controller.dto.ScreenFilter;
import com.gr74.booking.controller.dto.SeatFilter;
import com.gr74.booking.controller.dto.SeatTypeFilter;
import com.gr74.booking.controller.dto.TheaterFilter;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;
import com.gr74.booking.model.Seat;
import com.gr74.booking.model.SeatType;
import com.gr74.booking.model.Theater;
import com.gr74.booking.repository.spec.ScreenSpecifications;
import com.gr74.booking.repository.spec.SeatSpecifications;
import com.gr74.booking.repository.spec.SeatTypeSpecifications;
import com.gr74.booking.repository.spec.TheaterSpecifications;

/**
 * Dynamic paged filters for the inventory listings on H2.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
class InventorySpecificationsTest {

    private static final String SEAT_ROW = "seatRow";
    private static final String SEAT_NUMBER = "seatNumber";
    private static final String SCREEN_1 = "Screen 1";

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TheaterRepository theaterRepository;

    @Autowired
    private ScreenRepository screenRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private SeatTypeRepository seatTypeRepository;

    private Theater downtown;
    private Screen screen1;
    private SeatType standard;
    private SeatType vip;

    @BeforeEach
    void seed() {
        downtown = em.persist(new Theater("Downtown IMAX", "Cairo", "EGP"));
        Theater uptown = em.persist(new Theater("Uptown Cinema", "Alexandria", "EGP"));

        standard = em.persist(new SeatType("STANDARD", new BigDecimal("1.00")));
        vip = em.persist(new SeatType("VIP", new BigDecimal("2.50")));

        screen1 = em.persist(new Screen(SCREEN_1, ScreenType.SCREEN_3D, downtown));
        em.persist(new Screen("Screen 2", ScreenType.FRONT_SCREEN, downtown));
        em.persist(new Screen("Main Hall", ScreenType.SCREEN_3D, uptown));

        // Two rows on screen1: row A standard, row B vip.
        em.persist(new Seat("A", 1, screen1, standard));
        em.persist(new Seat("A", 2, screen1, standard));
        em.persist(new Seat("B", 1, screen1, vip));

        em.flush();
        em.clear();
    }

    // ---- Theaters ----

    @Test
    void theaterNameFilterIsCaseInsensitiveSubstring() {
        Page<Theater> page = theaterRepository.findAll(
                TheaterSpecifications.from(new TheaterFilter("imax", null)), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(Theater::getName).containsExactly("Downtown IMAX");
    }

    @Test
    void theaterLocationFilterMatches() {
        Page<Theater> page = theaterRepository.findAll(
                TheaterSpecifications.from(new TheaterFilter(null, "alex")), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(Theater::getName).containsExactly("Uptown Cinema");
    }

    @Test
    void emptyTheaterFilterReturnsAllPaged() {
        Page<Theater> page = theaterRepository.findAll(
                TheaterSpecifications.from(new TheaterFilter(null, null)), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void theaterPagingLimitsPageSize() {
        Page<Theater> page = theaterRepository.findAll(
                TheaterSpecifications.from(new TheaterFilter(null, null)), PageRequest.of(0, 1));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    // ---- Screens (scoped to a theater) ----

    @Test
    void screensAreScopedToTheirTheater() {
        Page<Screen> page = screenRepository.findAll(
                ScreenSpecifications.from(downtown.getId(), new ScreenFilter(null, null)), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(Screen::getName)
                .containsExactlyInAnyOrder(SCREEN_1, "Screen 2");
    }

    @Test
    void screenFilterByTypeWithinTheater() {
        Page<Screen> page = screenRepository.findAll(
                ScreenSpecifications.from(downtown.getId(), new ScreenFilter(null, ScreenType.SCREEN_3D)),
                PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(Screen::getName).containsExactly(SCREEN_1);
    }

    // ---- Seats (scoped to a screen, two-step seatType fetch) ----

    @Test
    void seatsAreScopedToScreenAndFetchSeatType() {
        Page<Seat> page = seatRepository.findSeatPage(
                SeatSpecifications.from(screen1.getId(), new SeatFilter(null, null)),
                PageRequest.of(0, 10, Sort.by(SEAT_ROW, SEAT_NUMBER)));
        assertThat(page.getContent()).extracting(Seat::getSeatRow).containsExactly("A", "A", "B");
        // seatType readable after em.clear() -> the two-step @EntityGraph fetch worked.
        assertThat(page.getContent()).allSatisfy(s -> assertThat(s.getSeatType().getName()).isNotBlank());
    }

    @Test
    void seatFilterBySeatTypeId() {
        Page<Seat> page = seatRepository.findSeatPage(
                SeatSpecifications.from(screen1.getId(), new SeatFilter(null, vip.getId())),
                PageRequest.of(0, 10, Sort.by(SEAT_ROW, SEAT_NUMBER)));
        assertThat(page.getContent()).extracting(Seat::getSeatRow).containsExactly("B");
    }

    @Test
    void seatFilterByRow() {
        Page<Seat> page = seatRepository.findSeatPage(
                SeatSpecifications.from(screen1.getId(), new SeatFilter("a", null)),
                PageRequest.of(0, 10, Sort.by(SEAT_ROW, SEAT_NUMBER)));
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent()).allSatisfy(s -> assertThat(s.getSeatRow()).isEqualTo("A"));
    }

    @Test
    void seatPagePreservesSortOrder() {
        Page<Seat> page = seatRepository.findSeatPage(
                SeatSpecifications.from(screen1.getId(), new SeatFilter(null, null)),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, SEAT_ROW)));
        // DESC by row: B first, then the two A seats.
        assertThat(page.getContent().get(0).getSeatRow()).isEqualTo("B");
    }

    // ---- Seat types ----

    @Test
    void seatTypeNameFilterMatches() {
        Page<SeatType> page = seatTypeRepository.findAll(
                SeatTypeSpecifications.from(new SeatTypeFilter("vip")), PageRequest.of(0, 10));
        assertThat(page.getContent()).extracting(SeatType::getName).containsExactly("VIP");
    }

    @Test
    void emptySeatTypeFilterReturnsAllPaged() {
        Page<SeatType> page = seatTypeRepository.findAll(
                SeatTypeSpecifications.from(new SeatTypeFilter(null)), PageRequest.of(0, 10));
        assertThat(page.getTotalElements()).isEqualTo(2);
    }
}
