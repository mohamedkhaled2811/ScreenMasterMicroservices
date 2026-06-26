package com.gr74.payment.provider;

/**
 * The seam between our payment service and whatever actually moves the money.
 *
 * <p>Today the only implementation is {@link FakePaymentProvider} ({@code @Profile("!real")}),
 * which simulates a gateway we fully control. When a real integration is needed, add a
 * {@code StripePaymentProvider} ({@code @Profile("real")}) implementing this same interface and
 * select it with {@code spring.profiles.active=real} — <b>no edits</b> to the controller, service,
 * or persistence. That add-only swap is the whole reason this interface exists.
 *
 * <p>Note: the field guide's scope guard keeps the lab on the fake provider (you learn more from
 * failures you can dial in); this interface just leaves the door open without pulling that work in.
 */
public interface PaymentProvider {

    /**
     * Attempt to charge. Implementations must be side-effect-only on the external provider; the
     * idempotency/persistence guard lives in the service layer, not here.
     */
    ProviderResult charge(ChargeCommand command);
}
