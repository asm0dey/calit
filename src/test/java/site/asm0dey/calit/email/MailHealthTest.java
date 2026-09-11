package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.health.SmtpHealthCheck;

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
    void everySmtpHealthCheckStateMapsToTheMailHealthStateItMeans() throws IOException {
        // MailHealth matches on the literal values SmtpHealthCheck publishes under data.state, so a
        // rename on either side is a silent break. The worst one is "reachable": a healthy
        // deployment would fall into probe()'s default branch and show "Email is not configured"
        // forever, on every owner's dashboard. Drive each spelling end to end -- through a real
        // MailHealth built over a real SmtpHealthCheck -- rather than comparing string constants,
        // so the assertion fails if the mapping breaks anywhere along the way.

        // A socket we own, so the port is genuinely open: "reachable" -> OK.
        try (var listening = new ServerSocket(0)) {
            var ok = new MailHealth(new SmtpHealthCheck(false, Optional.of("127.0.0.1"), listening.getLocalPort()));
            assertEquals(
                    MailHealth.State.OK,
                    ok.status().state(),
                    "an SMTP port that accepts connections must map to OK -- this is the 'reachable' spelling");
        }

        // Port 2 refuses fast: "unreachable" -> UNREACHABLE (never the UNCONFIGURED default).
        var down = new MailHealth(new SmtpHealthCheck(false, Optional.of("localhost"), 2));
        assertEquals(
                MailHealth.State.UNREACHABLE,
                down.status().state(),
                "a configured-but-dead SMTP host must map to UNREACHABLE, not the UNCONFIGURED default");

        var mocked = new SmtpHealthCheck(true, Optional.empty(), 587).call();
        assertEquals(
                "mocked-or-unconfigured",
                mocked.getData().orElseThrow().get("state"),
                "the mocked/unconfigured spelling is MailHealth's default branch");
    }
}
