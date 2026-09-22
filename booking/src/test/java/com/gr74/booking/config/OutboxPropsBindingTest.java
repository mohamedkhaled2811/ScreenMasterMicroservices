package com.gr74.booking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Binds the {@code booking.outbox.*} properties via {@code @ConfigurationPropertiesScan}.
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