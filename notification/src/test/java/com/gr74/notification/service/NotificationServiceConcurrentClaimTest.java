package com.gr74.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gr74.notification.messaging.BookingConfirmedEvent;
import com.gr74.notification.repository.ProcessedEventRepository;

/**
 * The <em>concurrent</em> losing-claim path: a {@code DataIntegrityViolationException} thrown by the
 * repository because <b>another consumer instance already claimed this event</b> — the exact race
 * {@code --scale notification=2} is meant to make visible. The repository is mocked to throw, so the
 * test drives the catch branch directly; the {@code @DataJpaTest} transaction is real, which is what
 * lets the service's rollback-only marking work.
 *
 * <p>Asserts the two things that matter: the service does not rethrow (the message is acked, not
 * redelivered forever) and it does not send a second email.
 */
@DataJpaTest
@Import(NotificationService.class)
@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceConcurrentClaimTest {

    @MockitoBean
    private ProcessedEventRepository processedEvents;

    @Autowired
    private NotificationService service;

    @Test
    @DisplayName("a concurrent claim that loses is handled gracefully and suppresses the send")
    void concurrentDuplicateClaimSuppressesTheSend(CapturedOutput output) {
        // A rival consumer committed the same eventId first; our insert is rejected by the PK.
        org.mockito.Mockito.when(processedEvents.claim(anyLong(), anyString(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        BookingConfirmedEvent event = new BookingConfirmedEvent(99L, 199L, "BK-00099", "user-1",
                Instant.parse("2026-09-13T12:00:00Z"));

        boolean sent = service.processBookingConfirmed(event);

        assertThat(sent).isFalse();                       // loser: ack-and-no-op
        assertThat(output).doesNotContain("ticket emailed");
        assertThat(output).contains("already processed, not sending again");
    }
}