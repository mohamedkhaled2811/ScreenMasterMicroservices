package com.gr74.notification.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.gr74.notification.config.NotificationProps;
import com.gr74.notification.exception.NotificationSendException;
import com.gr74.notification.messaging.TicketSeat;

/**
 * What can actually break in {@link EmailChannel}: <b>the templates</b>.
 *
 * <p>{@code JavaMailSender} is Spring's and works; SMTP is a protocol. The thing that silently fails
 * in production is a template variable that was renamed on one side and not the other — Thymeleaf
 * renders a missing variable as <em>empty</em> rather than throwing, so the failure mode is a
 * customer receiving a ticket with a blank seat number, and nothing in any log. So these tests render
 * the REAL templates with a real Thymeleaf engine and assert the facts appear in the output.
 *
 * <p>The mail sender is mocked: the point is what we hand to it, not that JavaMail can open a socket.
 * No Mailpit, no GreenMail — an integration test against a mail server would prove Spring's code, not
 * ours, and would be the slowest test in the suite.
 */
@ExtendWith(MockitoExtension.class)
class EmailChannelTest {

    private static final String RECIPIENT = "alice@screenmaster.local";

    @Mock
    private JavaMailSenderImpl mailSender;

    @Captor
    private ArgumentCaptor<MimeMessage> sentMessage;

    private EmailChannel channel;

    @BeforeEach
    void setUp() {
        // A real engine over the real classpath templates — that is the whole point of this class.
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        // SpringTemplateEngine, not the bare TemplateEngine: Boot's auto-configuration builds this
        // one, and it evaluates expressions with SpringEL. A bare TemplateEngine falls back to OGNL,
        // which is not on the classpath — so the test would fail for a reason production never hits,
        // and worse, it would be testing a different expression dialect than the one that ships.
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        NotificationProps props = new NotificationProps(
                new NotificationProps.Mail("tickets@screenmaster.local", "ScreenMaster"),
                new NotificationProps.Image("https://image.tmdb.org/t/p", "w780"),
                new NotificationProps.Identity("http://keycloak:8180", "cinema",
                        Duration.ofMinutes(10), 10_000));

        channel = new EmailChannel(mailSender, engine, props);
    }

    /** Renders through the channel and returns the MIME body as a String. */
    private String renderThroughChannel(Notification notification) throws Exception {
        // A real (unconnected) sender just to mint a MimeMessage; nothing is ever transmitted.
        given(mailSender.createMimeMessage()).willReturn(new JavaMailSenderImpl().createMimeMessage());

        channel.send(notification);

        then(mailSender).should().send(sentMessage.capture());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sentMessage.getValue().writeTo(out);
        // Quoted-printable encodes long lines with soft breaks ("=\r\n"); undo them so assertions can
        // match text that happens to straddle a line boundary.
        return out.toString(StandardCharsets.UTF_8).replace("=\r\n", "");
    }

    @Test
    @DisplayName("the confirmation template renders every ticket fact into the body")
    void confirmedTemplateRendersTheTicket() throws Exception {
        String body = renderThroughChannel(new Notification(RECIPIENT,
                "Your tickets for The Matrix", "booking-confirmed",
                Map.of("bookingReference", "BK-00001",
                        "movieTitle", "The Matrix",
                        "posterUrl", "https://image.tmdb.org/t/p/w780/matrix.jpg",
                        "showtime", "Fri 25 Sep 2026, 19:30",
                        "theaterName", "Downtown Cinema",
                        "screenName", "Screen 1",
                        "seats", List.of(new TicketSeat("E5", "STANDARD", new BigDecimal("18.00")),
                                new TicketSeat("E6", "STANDARD", new BigDecimal("18.00"))),
                        "totalAmount", new BigDecimal("36.00"),
                        "currency", "EGP")));

        // Each of these is a distinct th:text binding. A renamed variable drops exactly one of them.
        assertThat(body)
                .contains("BK-00001")
                .contains("The Matrix")
                .contains("Fri 25 Sep 2026, 19:30")
                .contains("Downtown Cinema")
                .contains("Screen 1")
                .contains("36.00")
                .contains("EGP");
        // The per-seat loop: both seats must appear, not just the first.
        assertThat(body).contains("E5").contains("E6");
        // The poster is a real <img src>, NOT a CSS background-image (unsupported in Outlook).
        assertThat(body).contains("https://image.tmdb.org/t/p/w780/matrix.jpg");
        assertThat(body).doesNotContain("background-image");
    }

    @Test
    @DisplayName("a ticket with no poster still renders every fact — the image band is simply dropped")
    void confirmedTemplateRendersWithoutAPoster() throws Exception {
        Map<String, Object> vars = new HashMap<>();
        vars.put("bookingReference", "BK-00002");
        vars.put("movieTitle", "Obscure Film");
        vars.put("posterUrl", null);          // TMDB has no artwork for this one
        vars.put("showtime", "Sat 26 Sep 2026, 21:00");
        vars.put("theaterName", "Downtown Cinema");
        vars.put("screenName", "Screen 2");
        vars.put("seats", List.of(new TicketSeat("A1", "VIP", new BigDecimal("40.00"))));
        vars.put("totalAmount", new BigDecimal("40.00"));
        vars.put("currency", "EGP");

        String body = renderThroughChannel(
                new Notification(RECIPIENT, "Your tickets", "booking-confirmed", vars));

        // The ticket is still complete — decoration is never load-bearing.
        assertThat(body).contains("BK-00002").contains("Obscure Film").contains("A1").contains("40.00");
        assertThat(body).doesNotContain("image.tmdb.org");
    }

    @Test
    @DisplayName("the refund template names the reason in words, never the raw enum")
    void rejectedTemplateRendersTheRefundNotice() throws Exception {
        String body = renderThroughChannel(new Notification(RECIPIENT,
                "Refund on its way — booking BK-00004", "booking-rejected",
                Map.of("bookingReference", "BK-00004", "reason", "EXPIRED", "paymentId", 900L)));

        assertThat(body).contains("BK-00004").contains("900");
        assertThat(body).contains("refunded");
        // The enum is a machine value; showing "EXPIRED" to a customer would be leaking an internal.
        assertThat(body).contains("had already been released").doesNotContain("EXPIRED");
    }

    @Test
    @DisplayName("developer comments never reach the customer — templates use parser-level comments")
    void authoringCommentsAreStrippedFromTheSentBody() throws Exception {
        String body = renderThroughChannel(new Notification(RECIPIENT, "x", "booking-confirmed",
                Map.of("bookingReference", "BK-00003", "seats", List.of())));

        // Thymeleaf PASSES PLAIN <!-- --> COMMENTS THROUGH into the output. The templates carry long
        // authoring notes (why tables, why no background-image, which client breaks what), and as
        // ordinary HTML comments every one of them was being transmitted to customers — visible to
        // anyone who hits "view source", and counted against the message size. They are written as
        // Thymeleaf parser-level comments (<!--/* ... */-->) instead, which are removed at render.
        // This test is what keeps it that way: an ordinary comment added later fails here.
        assertThat(body)
                .doesNotContain("THE TICKET EMAIL")
                .doesNotContain("TABLES FOR LAYOUT")
                .doesNotContain("Outlook")
                .doesNotContain("<!--");
    }

    @Test
    @DisplayName("an unknown template name fails as a send error rather than an opaque crash")
    void unknownTemplateBecomesACodedSendFailure() {
        assertThatThrownBy(() -> channel.send(new Notification(RECIPIENT, "x", "no-such-template",
                Map.of("bookingReference", "BK-00009"))))
                .isInstanceOf(NotificationSendException.class)
                .hasMessageContaining("no-such-template");
    }

    @Test
    @DisplayName("an SMTP failure is rethrown, never swallowed — the claim must roll back")
    void smtpFailureIsRethrown() {
        given(mailSender.createMimeMessage()).willReturn(new JavaMailSenderImpl().createMimeMessage());
        willThrow(new MailSendException("connection refused")).given(mailSender).send(any(MimeMessage.class));

        // If this were caught and logged, the caller would commit a claim for an email that never
        // went out — marking a customer's lost ticket as successfully processed.
        assertThatThrownBy(() -> channel.send(new Notification(RECIPIENT, "x", "booking-rejected",
                Map.of("bookingReference", "BK-00010", "reason", "CANCELLED", "paymentId", 1L))))
                .isInstanceOf(NotificationSendException.class)
                .hasRootCauseInstanceOf(MailSendException.class);
    }
}
