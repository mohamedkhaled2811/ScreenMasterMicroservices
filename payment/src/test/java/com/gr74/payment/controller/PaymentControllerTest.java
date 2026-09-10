package com.gr74.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.payment.dto.PaymentSessionResponse;
import com.gr74.payment.exception.BookingNotFoundException;
import com.gr74.payment.exception.BookingNotPayableException;
import com.gr74.payment.exception.CurrencyNotSupportedException;
import com.gr74.payment.exception.ForbiddenBookingException;
import com.gr74.payment.gateway.GatewayRegistry;
import com.gr74.payment.model.PaymentAttemptStatus;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.model.PaymentStatus;
import com.gr74.payment.service.PaymentService;
import com.gr74.payment.service.PaymentService.SessionOutcome;

/**
 * The HTTP contract of {@code POST /payments} — status codes and the coded error bodies.
 *
 * <p>The <em>rules</em> are tested in {@code PaymentServiceTest}; this slice asserts that each rule's
 * outcome reaches the client in the documented shape, because the {@code code} in the ProblemDetail is
 * the stable contract a client branches on — not the message, and not the HTTP status alone.
 */
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    private static final String PATH = "/payments";
    private static final String USER = "11111111-1111-1111-1111-111111111111";
    private static final String CODE = "$.code";
    private static final String BODY = """
            {"bookingId":1001,"gateway":"PAYMOB"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PaymentService paymentService;
    @MockitoBean private GatewayRegistry gatewayRegistry;

    private static PaymentSessionResponse session() {
        return new PaymentSessionResponse(500L, 900L, 1001L,
                PaymentStatus.PENDING, PaymentAttemptStatus.PENDING, PaymentGatewayType.PAYMOB,
                "https://accept.paymob.com/checkout/abc", Instant.parse("2026-08-31T20:05:00Z"),
                new BigDecimal("300.00"), "EGP");
    }

    @Test
    @DisplayName("a new attempt returns 201 with the checkout URL")
    void newAttemptReturns201() throws Exception {
        given(paymentService.createSession(any(), anyString()))
                .willReturn(new SessionOutcome(session(), true));

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkoutUrl").value("https://accept.paymob.com/checkout/abc"))
                .andExpect(jsonPath("$.paymentId").value(500))
                .andExpect(jsonPath("$.attemptId").value(900))
                .andExpect(jsonPath("$.currency").value("EGP"));
    }

    @Test
    @DisplayName("a reused session returns 200, so a client can tell the difference")
    void reusedSessionReturns200() throws Exception {
        given(paymentService.createSession(any(), anyString()))
                .willReturn(new SessionOutcome(session(), false));

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a missing X-User-Id is a coded 400, not a 500")
    void missingUserHeaderReturns400() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE).value("PAYMENT_VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("an unknown gateway name is a coded 400, never a leaked type-mismatch 500")
    void unknownGatewayReturns400() throws Exception {
        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":1001,\"gateway\":\"NOT_A_GATEWAY\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(CODE).value("PAYMENT_VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("someone else's booking is 403 PAYMENT_FORBIDDEN")
    void foreignBookingReturns403() throws Exception {
        willThrow(new ForbiddenBookingException(1001L))
                .given(paymentService).createSession(any(), anyString());

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath(CODE).value("PAYMENT_FORBIDDEN"));
    }

    @Test
    @DisplayName("an unknown booking is 404 PAYMENT_BOOKING_NOT_FOUND")
    void unknownBookingReturns404() throws Exception {
        willThrow(new BookingNotFoundException(1001L))
                .given(paymentService).createSession(any(), anyString());

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath(CODE).value("PAYMENT_BOOKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("an expired seat hold is 409 PAYMENT_BOOKING_EXPIRED, and says to rebook")
    void expiredBookingReturns409() throws Exception {
        willThrow(BookingNotPayableException.expired(1001L))
                .given(paymentService).createSession(any(), anyString());

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(CODE).value("PAYMENT_BOOKING_EXPIRED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("new booking")));
    }

    @Test
    @DisplayName("an already-paid booking is 409 PAYMENT_ALREADY_PAID")
    void alreadyPaidReturns409() throws Exception {
        willThrow(BookingNotPayableException.alreadyPaid(1001L))
                .given(paymentService).createSession(any(), anyString());

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(CODE).value("PAYMENT_ALREADY_PAID"));
    }

    @Test
    @DisplayName("a gateway that cannot settle the currency is 400 with the alternatives named")
    void currencyMismatchReturns400() throws Exception {
        willThrow(new CurrencyNotSupportedException(PaymentGatewayType.PAYMOB, "USD",
                Set.of(PaymentGatewayType.STRIPE)))
                .given(paymentService).createSession(any(), anyString());

        mockMvc.perform(post(PATH).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(CODE).value("PAYMENT_CURRENCY_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("STRIPE")));
    }

    @Test
    @DisplayName("the gateway list is filtered by currency")
    void listsGatewaysForCurrency() throws Exception {
        given(gatewayRegistry.availableFor("EGP"))
                .willReturn(Set.of(PaymentGatewayType.PAYMOB, PaymentGatewayType.SANDBOX));

        mockMvc.perform(get(PATH + "/gateways").param("currency", "EGP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EGP"))
                .andExpect(jsonPath("$.gateways", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("the gateway list with no currency returns everything registered")
    void listsAllGateways() throws Exception {
        given(gatewayRegistry.available()).willReturn(Set.of(PaymentGatewayType.SANDBOX));

        mockMvc.perform(get(PATH + "/gateways"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateways[0]").value("SANDBOX"));
    }
}
