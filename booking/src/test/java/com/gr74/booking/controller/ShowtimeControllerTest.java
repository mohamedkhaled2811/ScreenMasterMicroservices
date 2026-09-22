package com.gr74.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.booking.config.SecurityConfig;
import com.gr74.booking.dto.CreateShowtimeRequest;
import com.gr74.booking.exception.CatalogUnavailableException;
import com.gr74.booking.exception.MovieNotInCatalogException;
import com.gr74.booking.exception.ResourceNotFoundException;
import com.gr74.booking.model.Screen;
import com.gr74.booking.model.ScreenType;
import com.gr74.booking.model.Showtime;
import com.gr74.booking.model.Theater;
import com.gr74.booking.service.ShowtimeService;

/**
 * Web-layer slice for {@link ShowtimeController}: status mapping and auth fence, service mocked.
 */
@WebMvcTest(ShowtimeController.class)
@Import(SecurityConfig.class)
class ShowtimeControllerTest {

    private static final String VALID_BODY = """
            {"movieId":603,"screenId":1,"showDate":"2026-07-10","showTime":"19:30","basePrice":12.50}
            """;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-03T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShowtimeService showtimeService;

    /** Any authenticated token — for reads. */
    private static JwtRequestPostProcessor userToken() {
        return jwt().jwt(jwt -> jwt.subject("11111111-1111-1111-1111-111111111111"));
    }

    /** A token carrying the ADMIN realm role — for writes. */
    private static JwtRequestPostProcessor adminToken() {
        return jwt().jwt(jwt -> jwt.subject("33333333-3333-3333-3333-333333333333"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    @Test
    void createReturns201WithMovieIdButNoTitle() throws Exception {
        given(showtimeService.create(any(CreateShowtimeRequest.class))).willReturn(sampleShowtime());

        mockMvc.perform(post("/showtimes")
                        .with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.movieId").value(603))
                .andExpect(jsonPath("$.screenId").value(1))
                .andExpect(jsonPath("$.showTime").value("19:30"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                // The response carries no title.
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void createWithoutTokenIs401() throws Exception {
        mockMvc.perform(post("/showtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BOOKING_UNAUTHORIZED"));
    }

    @Test
    void createAsUserIs403() throws Exception {
        mockMvc.perform(post("/showtimes")
                        .with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BOOKING_FORBIDDEN"));
    }

    @Test
    void unknownMovieReturns404ProblemDetail() throws Exception {
        given(showtimeService.create(any(CreateShowtimeRequest.class)))
                .willThrow(new MovieNotInCatalogException(603L));

        mockMvc.perform(post("/showtimes")
                        .with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BOOKING_MOVIE_NOT_FOUND"));
    }

    @Test
    void catalogDownReturns503ProblemDetail() throws Exception {
        given(showtimeService.create(any(CreateShowtimeRequest.class)))
                .willThrow(new CatalogUnavailableException(603L, new RuntimeException("connection refused")));

        mockMvc.perform(post("/showtimes")
                        .with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BOOKING_CATALOG_UNAVAILABLE"));
    }

    @Test
    void missingRequiredFieldReturns400ProblemDetail() throws Exception {
        // No movieId → bean validation fails before the service is touched.
        mockMvc.perform(post("/showtimes")
                        .with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"screenId\":1,\"showDate\":\"2026-07-10\",\"showTime\":\"19:30\",\"basePrice\":12.50}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BOOKING_VALIDATION_ERROR"));
    }

    @Test
    void getMissingShowtimeReturns404ProblemDetail() throws Exception {
        given(showtimeService.getById(anyLong())).willThrow(ResourceNotFoundException.showtime(7L));

        mockMvc.perform(get("/showtimes/{id}", 7L).with(userToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_SHOWTIME_NOT_FOUND"));
    }

    @Test
    void byMovieReturnsListShape() throws Exception {
        given(showtimeService.findByMovie(603L)).willReturn(java.util.List.of(sampleShowtime()));

        mockMvc.perform(get("/showtimes/movie/{movieId}", 603L).with(userToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].movieId").value(603))
                .andExpect(jsonPath("$[0].basePrice").value(12.50));
    }

    /**
     * A showtime with ids stamped via reflection (no real transaction).
     */
    private static Showtime sampleShowtime() {
        Theater theater = new Theater("T", null, "EGP");
        Screen screen = new Screen("Screen 1", ScreenType.SCREEN_3D, theater);
        ReflectionTestUtils.setField(screen, "id", 1L);
        Showtime showtime = new Showtime(
                603L, screen, LocalDate.of(2026, 7, 10), LocalTime.of(19, 30), new BigDecimal("12.50"));
        ReflectionTestUtils.setField(showtime, "id", 100L);
        return showtime;
    }
}
