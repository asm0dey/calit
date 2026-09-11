package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// #195: MailHealth is the single seam four UI surfaces read to decide whether to warn about mail.
@QuarkusTest
class MailHealthTest {

    @Inject
    MailHealth mailHealth;

    @BeforeEach
    void clean() {
        QuarkusTransaction.requiringNew().run(() -> EmailOutbox.deleteAll());
    }

    @Test
    void mockedMailerCountsAsUnconfigured() {
        // %test runs quarkus.mailer.mock=true -- a deployment that sends nothing.
        var status = mailHealth.status();
        assertEquals(MailHealth.State.UNCONFIGURED, status.state());
        assertTrue(status.degraded(), "unconfigured mail is degraded: guests get no confirmations");
        assertTrue(status.unconfigured());
        assertFalse(status.unreachable(), "unconfigured and unreachable are different operator problems");
    }

    @Test
    void deadLettersAreCounted() {
        assertEquals(0L, mailHealth.status().deadLetters(), "clean outbox has no dead letters");
        assertFalse(mailHealth.status().hasDeadLetters());

        QuarkusTransaction.requiringNew().run(() -> {
            park("dead@example.com", null); // next_attempt_at null = given up on
            park("retrying@example.com", Instant.now()); // still scheduled = not dead
        });

        assertEquals(
                1L,
                mailHealth.status().deadLetters(),
                "only rows we've given up on count -- a row still in backoff is not a dead letter");
        assertTrue(mailHealth.status().hasDeadLetters());
    }

    @Test
    void sentRowsAreNotDeadLetters() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = parkRow("delivered@example.com", null);
            r.sentAt = Instant.now(); // delivered on a retry, then parked-row bookkeeping cleared it
            r.persist();
        });

        assertEquals(0L, mailHealth.status().deadLetters(), "a row that eventually sent is not a dead letter");
    }

    @Test
    void undeliveredForFindsMailStillOwedToAnAddress() {
        assertFalse(mailHealth.undeliveredFor("guest@example.com"), "nothing parked -> nothing owed");

        QuarkusTransaction.requiringNew().run(() -> park("guest@example.com", Instant.now()));
        assertTrue(mailHealth.undeliveredFor("guest@example.com"), "a parked, unsent row means mail is owed");

        assertFalse(mailHealth.undeliveredFor("someone.else@example.com"), "scoped to the address asked about");
    }

    @Test
    void undeliveredForCountsGivenUpMailToo() {
        QuarkusTransaction.requiringNew().run(() -> park("gone@example.com", null));
        assertTrue(
                mailHealth.undeliveredFor("gone@example.com"),
                "a dead letter is the strongest form of undelivered -- it will never arrive");
    }

    @Test
    void undeliveredForIgnoresMailThatWasSent() {
        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox r = parkRow("ok@example.com", null);
            r.sentAt = Instant.now();
            r.persist();
        });
        assertFalse(mailHealth.undeliveredFor("ok@example.com"), "a sent row owes nothing");
    }

    private static void park(String recipient, Instant nextAttemptAt) {
        parkRow(recipient, nextAttemptAt);
    }

    private static EmailOutbox parkRow(String recipient, Instant nextAttemptAt) {
        var r = new EmailOutbox();
        r.recipient = recipient;
        r.subject = "Subj";
        r.htmlBody = "<p>hi</p>";
        r.attempts = 0;
        r.nextAttemptAt = nextAttemptAt;
        r.createdAt = Instant.now();
        r.persist();
        return r;
    }

    @Test
    void reachableStateSpellingMatchesTheHealthCheck() {
        // MailHealth matches on the literal values SmtpHealthCheck publishes under data.state.
        // If either side is renamed, the banner would silently report UNCONFIGURED forever --
        // a wrong-but-plausible state, which is the worst kind of silent break. Pin both spellings.
        var reachable =
                new site.asm0dey.calit.health.SmtpHealthCheck(false, java.util.Optional.of("localhost"), 2).call();
        // Port 2 refuses fast -> "unreachable", proving the unreachable spelling.
        assertEquals(
                MailHealth.STATE_UNREACHABLE,
                reachable.getData().orElseThrow().get("state"),
                "MailHealth.STATE_UNREACHABLE must equal what SmtpHealthCheck publishes");

        var mocked = new site.asm0dey.calit.health.SmtpHealthCheck(true, java.util.Optional.empty(), 587).call();
        assertEquals(
                "mocked-or-unconfigured",
                mocked.getData().orElseThrow().get("state"),
                "the mocked/unconfigured spelling is MailHealth's default branch");
    }
}
