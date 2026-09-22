package com.gr74.notification.channel;

import com.gr74.notification.exception.NotificationSendException;

/**
 * Delivers a {@link Notification}. Must throw on failure so the claim rolls back and the message redelivers.
 */
public interface NotificationChannel {

    /**
     * Delivers the message.
     *
     * @throws NotificationSendException delivery failed — the caller lets this propagate
     */
    void send(Notification notification);
}
