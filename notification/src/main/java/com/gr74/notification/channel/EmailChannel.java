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

/**
 * Delivers a {@link Notification} as an HTML email over SMTP — the class that finally turns
 * {@code NotificationService}'s {@code log.info} into a real side effect (BUILD_PLAN 4.3).
 *
 * <p><b>What this class does not decide.</b> It does not know what a booking is, which movie was
 * seen, or why the message is being sent. It receives a recipient, a subject, a template name and a
 * variable map, and turns them into bytes on a socket. That split is the whole point of the
 * {@link NotificationChannel} seam: business meaning stays in the service, transport lives here, and
 * an {@code SmsChannel} can be added without touching either the service or the listener.
 *
 * <p><b>Why {@code MimeMessage} rather than {@code SimpleMailMessage}.</b> A ticket is HTML — it has
 * a poster image, a layout, styling. {@code SimpleMailMessage} can only send plain text. The
 * multipart form here also carries a plain-text alternative alongside the HTML, which matters more
 * than it looks: a mail client that cannot render HTML (or a user who forces text) still gets a
 * readable ticket, and spam filters score multipart/alternative mail better than HTML-only.
 *
 * <p><b>The failure contract.</b> Every failure becomes a {@link NotificationSendException} and is
 * rethrown, never logged-and-swallowed. The caller is inside the transaction holding the idempotency
 * claim, so the throw rolls that claim back and the broker redelivers — the only reason a retry can
 * ever succeed. See {@link NotificationChannel}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    /**
     * Where {@link Notification#templateName()} resolves to. Thymeleaf's Boot auto-configuration
     * already prefixes {@code classpath:/templates/} and suffixes {@code .html}, so a logical name
     * of {@code booking-confirmed} becomes {@code templates/email/booking-confirmed.html}.
     */
    private static final String TEMPLATE_PREFIX = "email/";

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final NotificationProps props;

    @Override
    public void send(Notification notification) {
        String html = render(notification);
        MimeMessage message = mailSender.createMimeMessage();
        try {
            // true = multipart, so an HTML body and its plain-text alternative can coexist.
            // The explicit UTF-8 is not optional: movie titles carry accents and non-Latin
            // scripts, and the JavaMail default encoding would mangle them.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(props.mail().from(), props.mail().fromName());
            helper.setTo(notification.recipient());
            helper.setSubject(notification.subject());
            // (plainText, html) — order matters. Mail clients render the LAST alternative they
            // understand, so the HTML must be second or every client shows the text fallback.
            helper.setText(plainTextFallback(notification), html);

            mailSender.send(message);
            log.info("Email sent — template={} to={}", notification.templateName(),
                    mask(notification.recipient()));
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            // Rethrow, always. See the class javadoc: swallowing here marks an unsent ticket as
            // processed. The cause carries the SMTP reply code, which is the whole diagnosis.
            throw new NotificationSendException(
                    "Could not send '" + notification.templateName() + "' to "
                            + mask(notification.recipient()), e);
        }
    }

    /**
     * Renders the HTML body from the template and its variables.
     *
     * <p>Wrapped separately from the send so a template error (a typo'd variable, a missing file)
     * reports as a template failure rather than an SMTP one — the two have completely different
     * fixes, and a retry will never repair the former.
     */
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

    /**
     * The plain-text alternative.
     *
     * <p>Deliberately minimal: the ticket detail lives in the HTML, and duplicating the full layout
     * in text would mean maintaining every ticket change twice. This gives a text-only client enough
     * to act on — what it is and the booking reference — and points at the HTML part.
     */
    private String plainTextFallback(Notification notification) {
        Object reference = notification.variables().get("bookingReference");
        return notification.subject()
                + (reference == null ? "" : "\n\nBooking reference: " + reference)
                + "\n\nThis email is best viewed in a mail client that displays HTML.";
    }

    /**
     * Masks an address for logging: {@code alice@screenmaster.local} → {@code a***@screenmaster.local}.
     *
     * <p>Logs get shipped, indexed, and read by people who have no business knowing who booked what.
     * The domain is kept because it is what you actually debug with (wrong domain = wrong realm); the
     * local part is what identifies a person.
     */
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
