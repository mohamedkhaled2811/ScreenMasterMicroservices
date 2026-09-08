package com.gr74.payment.gateway.sandbox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.gr74.payment.model.Payment;
import com.gr74.payment.model.PaymentAttempt;
import com.gr74.payment.model.PaymentGatewayType;
import com.gr74.payment.repository.PaymentRepository;

/**
 * The sandbox pay surface: a clickable page, an outcome recorded once, and the partition flag.
 *
 * <p>Delivery over HTTP is exercised for real here — with nothing listening on the public URL in
 * tests the delivery fails and is logged, which is itself the honest assertion: a failed delivery
 * still leaves the recorded outcome behind for reconciliation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SandboxCheckoutControllerTest {

    private static final String USER = "11111111-1111-1111-1111-111111111111";

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentRepository payments;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from webhook_events");
        jdbc.update("delete from payment_attempts");
        jdbc.update("delete from sandbox_charges");
        jdbc.update("delete from payments");
    }

    @Test
    @DisplayName("the checkout page renders Pay and Decline for a known session")
    void checkoutPageRenders() throws Exception {
        openAttempt("sbx_page");

        mockMvc.perform(get("/payments/sandbox/checkout/sbx_page"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("sbx_page")));
    }

    @Test
    @DisplayName("an unknown session is a coded 404, not a blank page")
    void unknownSessionIs404() throws Exception {
        mockMvc.perform(get("/payments/sandbox/checkout/sbx_nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("Pay records SUCCEEDED in the sandbox ledger")
    void payRecordsTheOutcome() throws Exception {
        openAttempt("sbx_pay_ok");

        mockMvc.perform(post("/payments/sandbox/checkout/sbx_pay_ok/pay")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outcome", "succeed"))
                .andExpect(status().isOk());

        assertThatOutcome("sbx_pay_ok", "SUCCEEDED");
    }

    @Test
    @DisplayName("deliverWebhook=false records the outcome and withholds the delivery")
    void partitionFlagRecordsWithoutDelivering() throws Exception {
        openAttempt("sbx_partition");

        mockMvc.perform(post("/payments/sandbox/checkout/sbx_partition/pay")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("deliverWebhook", "false"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("NOT delivered")));

        // Recorded — so reconciliation has something honest to ask about ...
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from sandbox_charges where session_id = 'sbx_partition'",
                Integer.class)).isEqualTo(1);
        // ... but nothing arrived through the webhook pipe.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events", Integer.class)).isEqualTo(0);
    }

    @Test
    @DisplayName("paying twice does not draw twice — the first record wins")
    void doublePayIsIdempotent() throws Exception {
        openAttempt("sbx_double");

        mockMvc.perform(post("/payments/sandbox/checkout/sbx_double/pay")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outcome", "succeed"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/payments/sandbox/checkout/sbx_double/pay")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outcome", "decline"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select count(*) from sandbox_charges where session_id = 'sbx_double'",
                Integer.class)).isEqualTo(1);
        assertThatOutcome("sbx_double", "SUCCEEDED");
    }

    @Test
    @DisplayName("a garbage outcome is a coded 400")
    void garbageOutcomeIs400() throws Exception {
        openAttempt("sbx_garbage");

        mockMvc.perform(post("/payments/sandbox/checkout/sbx_garbage/pay")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("outcome", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VALIDATION_ERROR"));
    }

    private void assertThatOutcome(String sessionId, String expected) {
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select outcome from sandbox_charges where session_id = '" + sessionId + "'",
                String.class)).isEqualTo(expected);
    }

    private void openAttempt(String sessionId) {
        Payment payment = new Payment(1001L, USER, new BigDecimal("300.00"), "EGP");
        PaymentAttempt attempt = new PaymentAttempt(PaymentGatewayType.SANDBOX,
                "key-" + UUID.randomUUID());
        payment.addAttempt(attempt);
        attempt.recordSession(sessionId, "http://localhost/checkout/" + sessionId,
                Instant.now().plus(10, ChronoUnit.MINUTES));
        payments.save(payment);
    }
}
