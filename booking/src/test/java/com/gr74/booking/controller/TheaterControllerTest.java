package com.gr74.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.booking.config.WebPagingConfig;
import com.gr74.booking.controller.dto.TheaterFilter;
import com.gr74.booking.dto.CreateScreenRequest;
import com.gr74.booking.dto.CreateTheaterRequest;
import com.gr74.booking.exception.BookingErrorCode;
import com.gr74.booking.exception.BookingException;
import com.gr74.booking.exception.DuplicateResourceException;
import com.gr74.booking.exception.ResourceNotFoundException;
import com.gr74.booking.model.ScreenType;
import com.gr74.booking.model.Theater;
import com.gr74.booking.service.TheaterService;

/**
 * Web-layer slice for {@link TheaterController}: verifies the JSON shape of the happy path and the RFC
 * 9457 {@code ProblemDetail} (with the stable {@code code}) of each failure — without a database (the
 * {@link TheaterService} is mocked). {@code GlobalExceptionHandler} is a {@code @RestControllerAdvice},
 * so the slice picks it up and renders the thrown exceptions.
 */
@WebMvcTest(TheaterController.class)
@Import(WebPagingConfig.class) // brings in VIA_DTO serialization + the max-page-size cap for the slice
class TheaterControllerTest {

    private static final String THEATERS_PATH = "/theaters";
    private static final String THEATER_SCREENS_PATH = "/theaters/{id}/screens";
    private static final String CODE_JSON_PATH = "$.code";
    private static final String DOWNTOWN_IMAX = "Downtown IMAX";
    private static final String VALIDATION_ERROR_CODE = "BOOKING_VALIDATION_ERROR";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TheaterService theaterService;

    @Test
    void createTheaterReturns201WithBody() throws Exception {
        given(theaterService.createTheater(any(CreateTheaterRequest.class)))
                .willReturn(new Theater(DOWNTOWN_IMAX, "Main St", "EGP"));

        mockMvc.perform(post(THEATERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Downtown IMAX\",\"location\":\"Main St\",\"currency\":\"EGP\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value(DOWNTOWN_IMAX))
                .andExpect(jsonPath("$.location").value("Main St"))
                .andExpect(jsonPath("$.currency").value("EGP"));
    }

    @Test
    void createTheaterWithBlankNameReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post(THEATERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"location\":\"Main St\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value(VALIDATION_ERROR_CODE));
    }

    @Test
    void badCurrencyReturns400ProblemDetail() throws Exception {
        // Currency is not free text: Payment routes gateways on it, so "egp" or "EGPP" would mean no
        // gateway could settle this theater's bookings. Reject at the edge, with a coded error.
        mockMvc.perform(post(THEATERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Downtown IMAX\",\"currency\":\"egp\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value(VALIDATION_ERROR_CODE));
    }

    @Test
    void missingCurrencyReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post(THEATERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Downtown IMAX\",\"location\":\"Main St\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(CODE_JSON_PATH).value(VALIDATION_ERROR_CODE));
    }

    @Test
    void duplicateTheaterReturns409ProblemDetail() throws Exception {
        given(theaterService.createTheater(any(CreateTheaterRequest.class)))
                .willThrow(new DuplicateResourceException("A theater named 'Downtown IMAX' already exists"));

        mockMvc.perform(post(THEATERS_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Downtown IMAX\",\"currency\":\"EGP\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value("BOOKING_DUPLICATE"));
    }

    @Test
    void createScreenUnderMissingTheaterReturns404ProblemDetail() throws Exception {
        given(theaterService.createScreen(anyLong(), any(CreateScreenRequest.class)))
                .willThrow(ResourceNotFoundException.theater(42L));

        mockMvc.perform(post(THEATER_SCREENS_PATH, 42L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Screen 1\",\"screenType\":\"SCREEN_3D\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value("BOOKING_THEATER_NOT_FOUND"));
    }

    @Test
    void createScreenWithUnknownScreenTypeReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post(THEATER_SCREENS_PATH, 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Screen 1\",\"screenType\":\"HOLOGRAM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value(VALIDATION_ERROR_CODE));
    }

    @Test
    void badTheaterIdTypeReturns400ProblemDetail() throws Exception {
        mockMvc.perform(get(THEATER_SCREENS_PATH, "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value(VALIDATION_ERROR_CODE));
    }

    @Test
    void deleteMissingTheaterReturns404ProblemDetail() throws Exception {
        doThrow(ResourceNotFoundException.theater(99L)).when(theaterService).deleteTheater(99L);

        mockMvc.perform(delete("/theaters/{id}", 99L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath(CODE_JSON_PATH).value("BOOKING_THEATER_NOT_FOUND"));
    }

    // ---- Listing: pagination + filtering ------------------------------------------------------

    @Test
    void listTheatersReturnsStablePagedModelEnvelope() throws Exception {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Theater> page = new PageImpl<>(List.of(new Theater(DOWNTOWN_IMAX, "Cairo", "EGP")), pageable, 1);
        given(theaterService.listTheaters(any(TheaterFilter.class), any(Pageable.class))).willReturn(page);

        mockMvc.perform(get(THEATERS_PATH))
                .andExpect(status().isOk())
                // VIA_DTO envelope: content array + a nested page metadata object (the stable contract).
                .andExpect(jsonPath("$.content[0].name").value(DOWNTOWN_IMAX))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void listTheatersClampsSizeToMaxPageSize() throws Exception {
        Page<Theater> empty = new PageImpl<>(List.of(), PageRequest.of(0, WebPagingConfig.MAX_PAGE_SIZE), 0);
        given(theaterService.listTheaters(any(TheaterFilter.class), any(Pageable.class))).willReturn(empty);

        mockMvc.perform(get(THEATERS_PATH).param("size", "1000000"))
                .andExpect(status().isOk())
                // the resolver clamped the oversized request down to the hard cap
                .andExpect(jsonPath("$.page.size").value(WebPagingConfig.MAX_PAGE_SIZE));
    }

    @Test
    void listTheatersWithUnknownSortFieldReturns400ProblemDetail() throws Exception {
        given(theaterService.listTheaters(any(TheaterFilter.class), any(Pageable.class)))
                .willThrow(new BookingException(BookingErrorCode.BOOKING_VALIDATION_ERROR,
                        "Cannot sort by 'password'"));

        mockMvc.perform(get(THEATERS_PATH).param("sort", "password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE_JSON_PATH).value(VALIDATION_ERROR_CODE));
    }
}
