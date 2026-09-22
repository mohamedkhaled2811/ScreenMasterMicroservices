package com.gr74.payment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.payment.config.SecurityConfig;
import com.gr74.payment.dto.RefundResponse;
import com.gr74.payment.exception.PaymentErrorCode;
import com.gr74.payment.exception.PaymentException;
import com.gr74.payment.exception.PaymentNotFoundException;
import com.gr74.payment.model.RefundStatus;
import com.gr74.payment.service.RefundService;

/**
 * HTTP contract of {@code POST /payments/{id}/refunds}: status codes and coded error bodies.
 */
@Import(SecurityConfig.class)
@WebMvcTest(RefundController.class)
class RefundControllerTest {

    private static final String CODE = "$.code";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RefundService refundService;

    private static RefundResponse pending() {
        return new RefundResponse(700L, 500L, new BigDecimal("200.00"), "EGP",
                RefundStatus.PENDING, "CUSTOMER_REQUEST", "sbx_ref_1", "key-1",
                Instant.parse("2026-09-06T20:16:00Z"));
    }

    /** A token carrying the ADMIN realm role — the only caller refunds accept. */
    private static JwtRequestPostProcessor adminToken() {
        return jwt().jwt(jwt -> jwt.subject("33333333-3333-3333-3333-333333333333"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** An ordinary authenticated user — must be refused with 403, not 500, not empty. */
    private static JwtRequestPostProcessor userToken() {
        return jwt().jwt(jwt -> jwt.subject("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    @DisplayName("an accepted refund returns 201 with the PENDING row")
    void acceptedRefundReturns201() throws Exception {
        given(refundService.requestRefund(anyLong(), any(), any(), any()))
                .willReturn(pending());

        mockMvc.perform(post("/payments/500/refunds").with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":200.00,\"reason\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(200.00))
                .andExpect(jsonPath("$.gatewayRefundId").value("sbx_ref_1"));
    }

    @Test
    @DisplayName("no token is 401 PAYMENT_UNAUTHORIZED with a coded body, not an empty response")
    void missingTokenReturns401() throws Exception {
        mockMvc.perform(post("/payments/500/refunds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":200.00}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE).value("PAYMENT_UNAUTHORIZED"));
    }

    @Test
    @DisplayName("a USER token is 403 PAYMENT_ACCESS_DENIED rather than 401")
    void userTokenReturns403() throws Exception {
        mockMvc.perform(post("/payments/500/refunds").with(userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":200.00}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath(CODE).value("PAYMENT_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("an empty body means a full-remaining refund — still 201, never a 400")
    void emptyBodyMeansFullRefund() throws Exception {
        given(refundService.requestRefund(anyLong(), isNull(), isNull(), isNull()))
                .willReturn(pending());

        mockMvc.perform(post("/payments/500/refunds").with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("refunding money that was never captured is 409 PAYMENT_NOT_REFUNDABLE")
    void uncapturedPaymentReturns409() throws Exception {
        willThrow(new PaymentException(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE,
                "Payment 500 is PENDING — only captured money can be refunded"))
                .given(refundService).requestRefund(anyLong(), any(), any(), any());

        mockMvc.perform(post("/payments/500/refunds").with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":200.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(CODE).value("PAYMENT_NOT_REFUNDABLE"));
    }

    @Test
    @DisplayName("a refund past capacity is 409 PAYMENT_REFUND_EXCEEDS_REMAINING")
    void overCapacityReturns409() throws Exception {
        willThrow(new PaymentException(PaymentErrorCode.PAYMENT_REFUND_EXCEEDS_REMAINING,
                "Refund of 800 exceeds the remaining refundable 700"))
                .given(refundService).requestRefund(anyLong(), any(), any(), any());

        mockMvc.perform(post("/payments/500/refunds").with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":800.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath(CODE).value("PAYMENT_REFUND_EXCEEDS_REMAINING"));
    }

    @Test
    @DisplayName("an unknown payment is 404 PAYMENT_NOT_FOUND")
    void unknownPaymentReturns404() throws Exception {
        willThrow(new PaymentNotFoundException("Payment 999 not found"))
                .given(refundService).requestRefund(anyLong(), any(), any(), any());

        mockMvc.perform(post("/payments/999/refunds").with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":200.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath(CODE).value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a negative amount is a coded 400, not a 500")
    void negativeAmountReturns400() throws Exception {
        mockMvc.perform(post("/payments/500/refunds").with(adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-5.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(CODE).value("PAYMENT_VALIDATION_ERROR"));
    }
}
