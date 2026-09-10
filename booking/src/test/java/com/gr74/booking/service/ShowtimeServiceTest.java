package com.gr74.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gr74.booking.client.CatalogClient;
import com.gr74.booking.dto.CreateShowtimeRequest;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.exception.DuplicateResourceException;
import com.gr74.booking.exception.MovieNotInCatalogException;
import com.gr74.booking.exception.ShowtimeHasBookingsException;
import com.gr74.booking.repository.BookingRepository;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.model.Theater;
import com.gr74.booking.repository.ShowtimeRepository;

/**
 * Unit test for the {@link ShowtimeService} create flow — the orchestration that realizes plan option
 * 5C. The point of these cases is the <em>ordering and isolation</em> of the two validation boundaries:
 * the intra-Booking screen check, then the cross-service Catalog check, then the slot pre-check — and
 * that a failure at either boundary short-circuits before anything is written.
 */
@ExtendWith(MockitoExtension.class)
class ShowtimeServiceTest {

    @Mock
    private ShowtimeRepository showtimeRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private TheaterService theaterService;

    @Mock
    private CatalogClient catalogClient;

    @InjectMocks
    private ShowtimeService showtimeService;

    private static final String SCREEN_NAME = "S1";

    private static final CreateShowtimeRequest REQUEST = new CreateShowtimeRequest(
            603L, 1L, LocalDate.of(2026, 7, 10), LocalTime.of(19, 30), new BigDecimal("12.50"));

    @Test
    void createValidatesScreenThenMovieThenPersists() {
        Screen screen = new Screen("Screen 1", ScreenType.SCREEN_3D, new Theater("T", null, "EGP"));
        given(theaterService.requireScreen(1L)).willReturn(screen);
        given(showtimeRepository.existsByScreenIdAndMovieIdAndShowDateAndShowTime(anyLong(), anyLong(), any(), any()))
                .willReturn(false);
        given(showtimeRepository.save(any(Showtime.class))).willAnswer(inv -> inv.getArgument(0));

        Showtime created = showtimeService.create(REQUEST);

        assertThat(created.getMovieId()).isEqualTo(603L);
        // The cross-service validation actually happened (5C).
        verify(catalogClient).verifyMovieExists(603L);
        verify(showtimeRepository).save(any(Showtime.class));
    }

    @Test
    void unknownMovieShortCircuitsBeforeSlotCheckAndSave() {
        given(theaterService.requireScreen(1L))
                .willReturn(new Screen(SCREEN_NAME, ScreenType.FRONT_SCREEN, new Theater("T", null, "EGP")));
        doThrow(new MovieNotInCatalogException(603L)).when(catalogClient).verifyMovieExists(603L);

        assertThatThrownBy(() -> showtimeService.create(REQUEST))
                .isInstanceOf(MovieNotInCatalogException.class);

        // A bad movie must cost nothing downstream: no slot query, no insert.
        verify(showtimeRepository, never())
                .existsByScreenIdAndMovieIdAndShowDateAndShowTime(anyLong(), anyLong(), any(), any());
        verify(showtimeRepository, never()).save(any());
    }

    @Test
    void catalogOutageShortCircuitsBeforeSave() {
        given(theaterService.requireScreen(1L))
                .willReturn(new Screen(SCREEN_NAME, ScreenType.FRONT_SCREEN, new Theater("T", null, "EGP")));
        doThrow(new CatalogUnavailableException(603L, new RuntimeException("timeout")))
                .when(catalogClient).verifyMovieExists(603L);

        assertThatThrownBy(() -> showtimeService.create(REQUEST))
                .isInstanceOf(CatalogUnavailableException.class);
        verify(showtimeRepository, never()).save(any());
    }

    @Test
    void duplicateSlotIsRejected() {
        given(theaterService.requireScreen(1L))
                .willReturn(new Screen(SCREEN_NAME, ScreenType.FRONT_SCREEN, new Theater("T", null, "EGP")));
        given(showtimeRepository.existsByScreenIdAndMovieIdAndShowDateAndShowTime(1L, 603L,
                REQUEST.showDate(), REQUEST.showTime())).willReturn(true);

        assertThatThrownBy(() -> showtimeService.create(REQUEST))
                .isInstanceOf(DuplicateResourceException.class);
        verify(showtimeRepository, never()).save(any());
    }

    @Test
    void deleteSucceedsWhenShowtimeHasNoBookings() {
        Showtime showtime = new Showtime(603L, null, REQUEST.showDate(), REQUEST.showTime(), REQUEST.basePrice());
        given(showtimeRepository.findById(7L)).willReturn(java.util.Optional.of(showtime));
        given(bookingRepository.existsByShowtimeId(7L)).willReturn(false);

        showtimeService.delete(7L);

        verify(showtimeRepository).delete(showtime);
    }

    @Test
    void deleteIsRejectedWhenShowtimeHasBookings() {
        Showtime showtime = new Showtime(603L, null, REQUEST.showDate(), REQUEST.showTime(), REQUEST.basePrice());
        given(showtimeRepository.findById(7L)).willReturn(java.util.Optional.of(showtime));
        given(bookingRepository.existsByShowtimeId(7L)).willReturn(true);

        assertThatThrownBy(() -> showtimeService.delete(7L))
                .isInstanceOf(ShowtimeHasBookingsException.class);

        verify(showtimeRepository, never()).delete(any());
    }
}
