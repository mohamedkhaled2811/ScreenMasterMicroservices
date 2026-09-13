package com.gr74.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Proves {@code @ConfigurationPropertiesScan} on {@code BookingApplication} really binds the
 * {@code booking.outbox.*} prefix — a silently-unbound props record is a defect (the relay would
 * read fallbacks forever without anyone noticing).
 *
 * <p>Overrides the two values so this test proves <em>binding</em>, not just that the record's
 * default-fallback constructor fires on absent properties. Everything else is the hermetic test
 * context (H2, Eureka off — see {@code src/test/resources/application.yml}).
 */
@SpringBootTest(properties = {
        "booking.outbox.relay-interval-millis=1500",
        "booking.outbox.relay-batch-size=7"})
class OutboxPropsBindingTest {

    @Autowired
    private OutboxProps props;

    @Test
    void bindsBookingOutboxProperties() {
        assertThat(props.relayIntervalMillis()).isEqualTo(1500L);
        assertThat(props.relayBatchSize()).isEqualTo(7);
    }
}