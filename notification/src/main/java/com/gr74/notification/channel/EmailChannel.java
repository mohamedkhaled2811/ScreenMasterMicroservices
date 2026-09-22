package com.gr74.notification.channel;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.gr74.notification.config.NotificationProps;
import com.gr74.notification.exception.NotificationSendException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Sends a {@link Notification} as HTML email over SMTP. Failures are rethrown so the claim rolls back. */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    /** Template directory prefix under {@code classpath:/templates/}. */
    private static final String TEMPLATE_PREFIX = "email/";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final NotificationProps props;

    @Override
    public void send(Notification notification) {
        String html = render(notification);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            // Multipart with UTF-8 so HTML and plain-text alternatives coexist.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(props.mail().from(), props.mail().fromName());
            helper.setTo(notification.recipient());
            helper.setSubject(notification.subject());
            // HTML second so clients render it over the text fallback.
            helper.setText(plainTextFallback(notification), html);

            mailSender.send(message);
            log.info("Email sent — template={} to={}", notification.templateName(),
                    mask(notification.recipient()));
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // Rethrow so the claim rolls back and the message redelivers.
            throw new NotificationSendException(
                    "Could not send '" + notification.templateName() + "' to "
                            + mask(notification.recipient()), e);
        }
    }

    /** Renders the HTML body from the template and its variables. */
    private String render(Notification notification) {
        try {
            Context context = new Context();
            context.setVariables(notification.variables());
            return templateEngine.process(TEMPLATE_PREFIX + notification.templateName(), context);
        } catch (RuntimeException e) {
            throw new NotificationSendException(
                    "Could not render email template '" + notification.templateName() + "'", e);
        }
    }

    /** Minimal plain-text alternative for text-only clients. */
    private String plainTextFallback(Notification notification) {
        Object reference = notification.variables().get("bookingReference");
        return notification.subject()
                + (reference == null ? "" : "\n\nBooking reference: " + reference)
                + "\n\nThis email is best viewed in a mail client that displays HTML.";
    }

    /** Masks an address for logging, keeping the domain. */
    private String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
