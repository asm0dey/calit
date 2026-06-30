package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OutboxSchedulerTest {

    @Inject
    OutboxScheduler scheduler;

    // Spy the seam: Mailer is @Singleton so @InjectMock Mailer is unusable.
    @InjectSpy
    MailSender mailSender;

    @Inject
    EntityManager em;

    @BeforeEach
    void init() {
        QuarkusTransaction.requiringNew()
                .run(() -> em.createNativeQuery("DELETE FROM email_outbox").executeUpdate());
    }

    @Test
    void dueRowIsSentAndMarked() {
        doNothing().when(mailSender).sendNow(any(), anyString(), anyString(), anyString(), any());
        Long id = QuarkusTransaction.requiringNew()
                .call(() -> EmailOutbox.enqueue("a@b.com", "S", "h", null, null, "prev"));

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew()
                .run(() -> assertNotNull(((EmailOutbox) EmailOutbox.findById(id)).sentAt, "marked sent"));
    }

    @Test
    void failedRetryAppliesBackoffAndStaysUnsent() {
        doThrow(new RuntimeException("still down"))
                .when(mailSender)
                .sendNow(any(), anyString(), anyString(), anyString(), any());
        Long id = QuarkusTransaction.requiringNew()
                .call(() -> EmailOutbox.enqueue("a@b.com", "S", "h", null, null, null));

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            assertNull(r.sentAt);
            assertEquals(1, r.attempts);
            assertEquals("still down", r.lastError);
            assertTrue(r.nextAttemptAt.isAfter(java.time.Instant.now()), "backed off into the future");
        });
    }

    @Test
    void deadRowIsNotClaimed() {
        doNothing().when(mailSender).sendNow(any(), anyString(), anyString(), anyString(), any());
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            Long x = EmailOutbox.enqueue("a@b.com", "S", "h", null, null, null);
            EmailOutbox r = EmailOutbox.findById(x);
            r.nextAttemptAt = null; // dead
            return x;
        });

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew()
                .run(() -> assertNull(((EmailOutbox) EmailOutbox.findById(id)).sentAt, "dead row never re-sent"));
    }

    @Test
    void deadlinedRowPastDeadlineIsMarkedDeadAndNotSent() {
        doNothing().when(mailSender).sendNow(any(), anyString(), anyString(), anyString(), any());
        // Due now (next_attempt_at <= now) but its usefulness deadline already passed.
        Long id = QuarkusTransaction.requiringNew()
                .call(() -> EmailOutbox.enqueue(
                        "a@b.com", "S", "h", null, java.time.Instant.now().minusSeconds(1), null));

        scheduler.dispatchDueMail();

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            assertNull(r.sentAt, "past-deadline mail is never sent");
            assertNull(r.nextAttemptAt, "past-deadline mail is marked dead");
        });
        // The send was never attempted for the expired row.
        verify(mailSender, never()).sendNow(any(), anyString(), anyString(), anyString(), any());
    }
}
