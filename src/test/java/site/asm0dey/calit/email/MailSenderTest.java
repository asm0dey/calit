package site.asm0dey.calit.email;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class MailSenderTest {

    // Spy on the ApplicationScoped bean so we can force sendNow to throw,
    // exercising the fallback-to-outbox path in send().
    @InjectSpy
    MailSender mailSender;

    @Test
    void failedSendIsParkedInOutbox() {
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender).sendNow(anyString(), anyString(), anyString(), any());

        mailSender.send("a@b.com", "Subj", "<p>hi</p>", new byte[]{9});

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.find("recipient", "a@b.com").firstResult();
            assertEquals("Subj", r.subject);
            assertEquals("smtp down", r.lastError);
            assertNull(r.sentAt);
        });
    }

    @Test
    void failedDeadlinedSendStoresTheDeadline() {
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender).sendNow(anyString(), anyString(), anyString(), any());
        // Fixed instant (not the system clock): the deadline is only stored + read back here, and
        // a second-precision value round-trips exactly through Postgres TIMESTAMPTZ (micro precision).
        java.time.Instant deadline = java.time.Instant.parse("2026-06-15T12:00:00Z");

        mailSender.send("d@b.com", "Reset", "<p>link</p>", null, deadline);

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.find("recipient", "d@b.com").firstResult();
            assertEquals(deadline, r.notAfter, "reset mail carries its usefulness deadline into the outbox");
        });
    }

    @Test
    void successfulSendLeavesOutboxEmpty() {
        doNothing().when(mailSender).sendNow(anyString(), anyString(), anyString(), any());

        mailSender.send("ok@b.com", "Subj", "<p>hi</p>", null);

        long parked = QuarkusTransaction.requiringNew().call(() ->
                EmailOutbox.count("recipient", "ok@b.com"));
        assertEquals(0L, parked);
    }
}
