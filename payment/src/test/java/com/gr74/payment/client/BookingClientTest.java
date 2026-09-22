package com.gr74.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.gr74.payment.exception.BookingNotFoundException;
import com.gr74.payment.exception.BookingServiceUnavailableException;

/**
 * Booking payability read and its fail-closed failure mapping.
 */
class BookingClientTest {

    private static final String URL = "lb://booking/bookings/1001/payability";

    private MockRestServiceServer server;
    private BookingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("lb://booking");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BookingClient(builder.build());
    }

    @Test
    @DisplayName("reads the payability slice, including the authoritative amount")
    void readsPayability() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"bookingId":1001,"userId":"user-1","status":"PENDING",
                         "expiresAt":"2026-08-31T20:15:00Z","totalAmount":300.00,"currency":"EGP"}""",
                        MediaType.APPLICATION_JSON));

        BookingPayability payability = client.fetchPayability(1001L);

        assertThat(payability.userId()).isEqualTo("user-1");
        assertThat(payability.isPending()).isTrue();
        assertThat(payability.totalAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(payability.currency()).isEqualTo("EGP");
        server.verify();
    }

    @Test
    @DisplayName("a 404 is a definitive 'no such booking', not an outage")
    void notFoundIsDefinitive() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // Retrying will never help, so this must NOT be reported as an outage.
        assertThatThrownBy(() -> client.fetchPayability(1001L))
                .isInstanceOf(BookingNotFoundException.class);
    }

    @Test
    @DisplayName("a 5xx is an outage — we fail closed rather than guess an amount")
    void serverErrorIsOutage() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.fetchPayability(1001L))
                .isInstanceOf(BookingServiceUnavailableException.class);
    }

    @Test
    @DisplayName("NO REGISTERED INSTANCE is an outage too — the bug live testing caught")
    void noInstanceAvailableIsOutage() {
        // Spring Cloud LoadBalancer throws a bare IllegalStateException when Eureka knows of no healthy
        // instance. It is NOT a RestClientException, so it used to escape the catch block entirely and
        // surface as an opaque 500 PAYMENT_INTERNAL_ERROR instead of a coded 503. Found by curling the
        // live service with no Booking registered; the mocked unit tests could never have seen it.
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("lb://booking")
                .requestInterceptor((request, body, execution) -> {
                    throw new IllegalStateException("No instances available for booking");
                });
        BookingClient unresolvable = new BookingClient(builder.build());

        assertThatThrownBy(() -> unresolvable.fetchPayability(1001L))
                .isInstanceOf(BookingServiceUnavailableException.class)
                .hasMessageContaining("No instances available");
    }

    @Test
    @DisplayName("an empty body is an outage, not a silently-null booking")
    void emptyBodyIsOutage() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchPayability(1001L))
                .isInstanceOf(BookingServiceUnavailableException.class);
    }
}
