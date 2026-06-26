package com.gr74.payment.provider;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.gr74.payment.config.PaymentProps;
import com.gr74.payment.model.PaymentStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The fake gateway: sleeps {@code payment.latency-millis} to mimic network round-trip, then
 * approves or declines by drawing against {@code payment.fail-rate}. Active in every profile
 * except {@code real} ({@code @Profile("!real")}) so a future {@code StripePaymentProvider}
 * ({@code @Profile("real")}) can take over with no other change.
 */
@Slf4j
@Component
@Profile("!real")
@RequiredArgsConstructor
public class FakePaymentProvider implements PaymentProvider {

    private final PaymentProps props;

    @Override
    public ProviderResult charge(ChargeCommand command) {
        sleepToSimulateLatency();

        boolean declined = ThreadLocalRandom.current().nextDouble() < props.failRate();
        PaymentStatus status = declined ? PaymentStatus.DECLINED : PaymentStatus.APPROVED;
        String reference = "FAKE-" + UUID.randomUUID();

        log.info("Fake charge for idempotencyKey={} amount={} -> {} (failRate={}, ref={})",
                command.idempotencyKey(), command.amount(), status, props.failRate(), reference);

        return new ProviderResult(status, reference);
    }

    /**
     * Sleep the configured latency. We restore the interrupt flag and fail loudly rather than
     * swallow {@link InterruptedException} — a charge thread being interrupted is not a normal
     * "payment declined", so it must not be reported as one.
     */
    private void sleepToSimulateLatency() {
        try {
            Thread.sleep(props.latencyMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Charge interrupted while simulating provider latency", e);
        }
    }
}
