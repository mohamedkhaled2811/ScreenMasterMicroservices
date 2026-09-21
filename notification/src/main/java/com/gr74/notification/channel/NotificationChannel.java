package com.gr74.notification.channel;

import com.gr74.notification.exception.NotificationSendException;

/**
 * A way to actually deliver a {@link Notification} — email today, SMS or push later.
 *
 * <p><b>An interface with one implementation is normally exactly the abstraction to delete</b>, and
 * this one is called out deliberately rather than smuggled in. It earns its place for two reasons:
 * a second channel (SMS) is a stated requirement, and — more immediately — it is the seam that makes
 * {@code NotificationService} unit-testable. The dedupe tests assert "sent exactly once, zero times
 * on redelivery"; with {@code JavaMailSender} inlined into the service, proving that needs a mail
 * server, and with this interface it needs a three-line stub. If SMS turns out to be hypothetical,
 * collapse this: put {@code JavaMailSender} straight in the service and delete the package.
 *
 * <p><b>The contract on failure is "throw".</b> A channel MUST NOT swallow a delivery failure. The
 * caller runs inside the transaction that holds the idempotency claim, so a thrown exception rolls
 * that claim back, the message is nacked, and RabbitMQ redelivers — which is the only reason a retry
 * can succeed. A channel that caught and logged would silently lose a customer's ticket while every
 * dashboard stayed green.
 */
public interface NotificationChannel {

    /**
     * Delivers the message, or throws.
     *
     * @throws NotificationSendException delivery failed — the caller must let this propagate so the
     *                                   claim rolls back and the broker redelivers
     */
    void send(Notification notification);
}
