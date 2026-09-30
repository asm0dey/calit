package site.asm0dey.calit.email;

import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class EmailOutboxTest {
    @Test
    void enqueuePersistsADueUnsentRow() {
        Long id = QuarkusTransaction
            .requiringNew()
            .call(() -> EmailOutbox.enqueue("a@b.com", "Subj", "<p>hi</p>", new byte[] {1, 2}, null, "boom"));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            assertThat(r).isNotNull();
            assertThat(r.recipient).isEqualTo("a@b.com");
            assertThat(r.attempts).isZero();
            assertThat(r.sentAt).isNull();
            assertThat(r.nextAttemptAt).as("enqueued rows are due immediately").isNotNull();
            assertThat(r.lastError).isEqualTo("boom");
        });
    }

    @Test
    void backoffBumpsAttemptsAndPushesNextAttempt() {
        Long id =
                QuarkusTransaction
            .requiringNew()
            .call(() -> EmailOutbox.enqueue("a@b.com", "S", "h", null, null, null));

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = EmailOutbox.findById(id);
            java.time.Instant before = r.nextAttemptAt;
            r.deadOrBackoff("smtp down");
            assertThat(r.attempts).isOne();
            assertThat(r.lastError).isEqualTo("smtp down");
            assertThat(r.nextAttemptAt.isAfter(before)).as("next attempt pushed into the future").isTrue();
        });
    }

    @Test
    void attemptCapMarksRowDead() {
        Long id =
                QuarkusTransaction
            .requiringNew()
            .call(() -> EmailOutbox.enqueue("a@b.com", "S", "h", null, null, null));

        QuarkusTransaction
            .requiringNew()
            .run(() -> {
                EmailOutbox r = EmailOutbox.findById(id);
                // next failure is the 10th -> dead
                r.attempts = 9;
                r.deadOrBackoff("still down");
                assertThat(r.attempts).isEqualTo(10);
                assertThat(r.nextAttemptAt).as("capped row is dead: excluded from the claim query").isNull();
            });
    }

    @Test
    void pastDeadlineAndMarkExpired() {
        var now = java.time.Instant.parse("2026-06-15T12:00:00Z");
        EmailOutbox r = new EmailOutbox();
        // No deadline -> never past it.
        r.notAfter = null;
        assertThat(r.pastDeadline(now)).isFalse();
        // Deadline in the future -> not yet.
        r.notAfter = now.plusSeconds(60);
        assertThat(r.pastDeadline(now)).isFalse();
        // Deadline in the past -> past it; markExpired kills the row without sending.
        r.notAfter = now.minusSeconds(1);
        assertThat(r.pastDeadline(now)).isTrue();
        r.markExpired();
        assertThat(r.nextAttemptAt).as("expired row is dead").isNull();
    }
}
