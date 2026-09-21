package com.gr74.booking.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.booking.config.SecurityConfig;
import com.gr74.booking.config.WebMvcConfig;
import com.gr74.booking.config.WebPagingConfig;
import com.gr74.booking.dto.CreateBookingRequest;
import com.gr74.booking.dto.MyBookingDto;
import com.gr74.booking.model.Booking;
import com.gr74.booking.model.BookingStatus;
import com.gr74.booking.model.PaymentStatus;
import com.gr74.booking.security.CurrentUserArgumentResolver;
import com.gr74.booking.service.BookingService;
import com.gr74.booking.service.MovieDataSource;

/**
 * Web-layer slice for {@link BookingController}. Imports {@link WebMvcConfig} so the real
 * {@link CurrentUserArgumentResolver} is registered, and the real {@link SecurityConfig} so every
 * case runs through the REAL filter chain (a slice does not component-scan it otherwise).
 *
 * <p>Identity is the JWT {@code sub}, never the old {@code X-User-Id} header. The
 * {@code jwt()} post-processor forges the token (subject = the user); the hermetic suite never
 * calls Keycloak because the forged authentication never reaches the {@code JwtDecoder}. Cases
 * below pin: the {@code sub} reaches the service, a missing token is a coded 401 (not a 500, not a
 * silent null), and a forged {@code X-User-Id} header is IGNORED — it cannot spoof the user.
 */
@WebMvcTest(BookingController.class)
@Import({WebMvcConfig.class, WebPagingConfig.class, SecurityConfig.class})
class BookingControllerTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String VICTIM = "22222222-2222-2222-2222-222222222222";
    private static final String CREATE_BODY = """
            {"showtimeId":1,"seatIds":[10,11]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BookingService bookingService;

    @Test
    void createReturns201WithSnapshottedFields() throws Exception {
        given(bookingService.create(eq(USER), any(CreateBookingRequest.class))).willReturn(sampleBooking());

        mockMvc.perform(post("/bookings")
                        .with(jwt().jwt(jwt -> jwt.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingReference").value("BK-ABCD1234"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.movieId").value(603))
                .andExpect(jsonPath("$.totalAmount").value(25.00))
                // The caller already knows who they are — userId is not echoed in the response.
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    @Test
    void missingTokenReturns401ProblemDetail() throws Exception {
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("BOOKING_UNAUTHORIZED"));
    }

    @Test
    void spoofedUserHeaderIsIgnoredAndCannotImpersonate() throws Exception {
        // The attacker holds a valid token for USER but sends the victim's id in the dead header.
        // The booking must be created for the TOKEN holder (USER) — the header changes nothing.
        given(bookingService.create(eq(USER), any(CreateBookingRequest.class))).willReturn(sampleBooking());

        mockMvc.perform(post("/bookings")
                        .with(jwt().jwt(jwt -> jwt.subject(USER)))
                        .header("X-User-Id", VICTIM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        org.mockito.Mockito.verify(bookingService).create(eq(USER), any(CreateBookingRequest.class));
    }

    @Test
    void nonJwtPrincipalIsRejectedWith401() throws Exception {
        // Strict JWT-only seam: even an AUTHENTICATED non-JWT principal resolves to nobody.
        // (@WithMockUser builds a plain UsernamePasswordAuthenticationToken — no `sub` to read.)
        mockMvc.perform(post("/bookings")
                        .with(user("someone"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BOOKING_UNAUTHORIZED"));
    }

    @Test
    void emptySeatsReturns400ProblemDetail() throws Exception {
        mockMvc.perform(post("/bookings")
                        .with(jwt().jwt(jwt -> jwt.subject(USER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showtimeId\":1,\"seatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOKING_VALIDATION_ERROR"));
    }

    @Test
    void myBookingsReturnsPagedEnvelopeWithTitles() throws Exception {
        MyBookingDto row = MyBookingDto.of(sampleBooking(), "The Matrix");
        // No source param -> defaults to COMPOSITION.
        given(bookingService.myBookings(eq(USER), any(), eq(MovieDataSource.COMPOSITION)))
                .willReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/bookings/my").with(jwt().jwt(jwt -> jwt.subject(USER))))
                .andExpect(status().isOk())
                // VIA_DTO PagedModel envelope: content[] + a nested page{} object.
                .andExpect(jsonPath("$.content[0].movieTitle").value("The Matrix"))
                .andExpect(jsonPath("$.content[0].bookingReference").value("BK-ABCD1234"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    void myBookingsWithoutTokenIs401() throws Exception {
        mockMvc.perform(get("/bookings/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BOOKING_UNAUTHORIZED"));
    }

    @Test
    void myBookingsWithSourceReadmodelSelectsWayB() throws Exception {
        MyBookingDto row = MyBookingDto.of(sampleBooking(), "The Matrix");
        given(bookingService.myBookings(eq(USER), any(), eq(MovieDataSource.READMODEL)))
                .willReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/bookings/my")
                        .with(jwt().jwt(jwt -> jwt.subject(USER)))
                        .param("source", "readmodel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].movieTitle").value("The Matrix"));
    }

    @Test
    void myBookingsRejectsUnknownSourceWithCoded400() throws Exception {
        mockMvc.perform(get("/bookings/my")
                        .with(jwt().jwt(jwt -> jwt.subject(USER)))
                        .param("source", "wat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOKING_VALIDATION_ERROR"));
    }

    @Test
    void myBookingsRejectsNonWhitelistedSort() throws Exception {
        mockMvc.perform(get("/bookings/my")
                        .with(jwt().jwt(jwt -> jwt.subject(USER)))
                        .param("sort", "userId"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOKING_VALIDATION_ERROR"));
    }

    /** A persisted-looking booking with one seat; ids stamped via reflection (no real transaction). */
    private static Booking sampleBooking() {
        Booking booking = new Booking("BK-ABCD1234", USER, 1L, 603L, new BigDecimal("25.00"), "EGP", Instant.parse("2026-07-08T12:15:00Z"));
        ReflectionTestUtils.setField(booking, "id", 100L);
        ReflectionTestUtils.setField(booking, "status", BookingStatus.PENDING);
        ReflectionTestUtils.setField(booking, "paymentStatus", PaymentStatus.PENDING);
        return booking;
    }
}
