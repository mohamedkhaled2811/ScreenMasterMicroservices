package com.gr74.notification.channel;

import java.util.Map;

/**
 * Decided-but-undelivered message: template name plus variables; each channel renders it.
 *
 * @param recipient    where to deliver — an email address today, a phone number for an SMS channel
 * @param subject      the message subject; ignored by channels without that concept
 * @param templateName logical template id, not a file path
 * @param variables    the data the template renders
 */
public record Notification(String recipient, String subject, String templateName,
        Map<String, Object> variables) {
}
