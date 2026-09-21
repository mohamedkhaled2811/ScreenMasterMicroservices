package com.gr74.notification.channel;

import java.util.Map;

/**
 * One message, fully decided but not yet delivered — the value object that crosses the seam between
 * <em>deciding what to say</em> ({@code NotificationService}) and <em>delivering it</em>
 * ({@link NotificationChannel}).
 *
 * <p><b>Why the body is a template name plus variables, not a rendered String.</b> If the service
 * rendered the HTML itself, the service would own email markup — and an SMS channel would have to be
 * handed HTML and strip it. Naming the template and passing the data keeps rendering a
 * <em>channel</em> concern: {@code EmailChannel} resolves {@code booking-confirmed} to
 * {@code templates/email/booking-confirmed.html}, and a future {@code SmsChannel} would resolve the
 * same name to a 160-character text template from the same variable map. The service does not change.
 *
 * @param recipient    where to deliver — an email address today, a phone number for an SMS channel
 * @param subject      the message subject; ignored by channels that have no such concept
 * @param templateName logical template id ({@code booking-confirmed}), NOT a file path — each
 *                     channel maps it into its own template directory and extension
 * @param variables    the data the template renders; keys must match the template's expressions
 */
public record Notification(String recipient, String subject, String templateName,
        Map<String, Object> variables) {
}
