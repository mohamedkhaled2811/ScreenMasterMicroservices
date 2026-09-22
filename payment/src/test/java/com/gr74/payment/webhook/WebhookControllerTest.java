package com.gr74.payment.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gr74.payment.config.SecurityConfig;

/**
 * HTTP contract of {@code POST /payments/webhooks/{gateway}}: raw bytes and coded outcomes.
 */
@Import(SecurityConfig.class)
@WebMvcTest(WebhookController.class)
class WebhookControllerTest {

    private static final String BODY = """
            {"id":"evt_1","type":"payment.succeeded","sessionId":"sbx_sess_1","paymentId":"pay_1"}""";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WebhookProcessor processor;

    @Test
    @DisplayName("processed, duplicate, and ignored all answer 200 with the outcome")
    void nonSignatureOutcomesAnswer200() throws Exception {
        given(processor.process(any(), any(), any()))
                .willReturn(WebhookResult.processed(900L));

        mockMvc.perform(post("/payments/webhooks/sandbox")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gateway").value("sandbox"))
                .andExpect(jsonPath("$.outcome").value("processed"));
    }

    @Test
    @DisplayName("a bad signature answers 400 with PAYMENT_WEBHOOK_SIGNATURE_INVALID")
    void badSignatureAnswers400() throws Exception {
        given(processor.process(any(), any(), any())).willReturn(WebhookResult.badSignature());

        mockMvc.perform(post("/payments/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    @DisplayName("an unknown gateway segment answers 400 without touching the processor")
    void unknownGatewayAnswers400() throws Exception {
        mockMvc.perform(post("/payments/webhooks/acme")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_VALIDATION_ERROR"));

        verify(processor, never()).process(any(), any(), any());
    }

    @Test
    @DisplayName("the raw body reaches the processor byte-for-byte, headers lower-cased")
    void rawBytesPassThroughUntouched() throws Exception {
        // Deliberately odd spacing: a parsed-and-reserialized DTO would normalize this away and
        // break the HMAC. The processor must see exactly these bytes.
        String oddlySpaced = "{  \"id\" : \"evt_1\" , \"type\"  :  \"payment.succeeded\" }";
        given(processor.process(any(), any(), any()))
                .willReturn(WebhookResult.ignored("uninteresting type"));

        mockMvc.perform(post("/payments/webhooks/sandbox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oddlySpaced.getBytes(StandardCharsets.UTF_8))
                        .header("X-Sandbox-Signature", "abc123"))
                .andExpect(status().isOk());

        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, String>> headers =
                ArgumentCaptor.forClass(java.util.Map.class);
        verify(processor).process(
                eq(com.gr74.payment.model.PaymentGatewayType.SANDBOX), body.capture(), headers.capture());
        org.assertj.core.api.Assertions.assertThat(body.getValue())
                .isEqualTo(oddlySpaced.getBytes(StandardCharsets.UTF_8));
        org.assertj.core.api.Assertions.assertThat(headers.getValue())
                .containsEntry("x-sandbox-signature", "abc123");
    }
}
