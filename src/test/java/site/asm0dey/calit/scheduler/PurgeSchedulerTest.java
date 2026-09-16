package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.privacy.TokenFixtures;

@QuarkusTest
class PurgeSchedulerTest {

    @Inject
    PurgeScheduler purger;

    private Long outbox(Instant createdAt, Instant sentAt, Instant nextAttemptAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var r = new EmailOutbox();
            r.recipient = "a@example.com";
            r.subject = "s";
            r.htmlBody = "<p>personal</p>";
            r.attempts = 0;
            r.createdAt = createdAt;
            r.sentAt = sentAt;
            r.nextAttemptAt = nextAttemptAt;
            r.persist();
            return r.id;
        });
    }

    private static long count(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count("id", id));
    }

    private static Instant daysAgo(int d) {
        return Instant.now().minus(d, ChronoUnit.DAYS);
    }

    @Test
    void sentMailOlderThanThirtyDaysGoes() {
        var id = outbox(daysAgo(40), daysAgo(31), null);
        purger.purge();
        assertEquals(0L, count(id));
    }

    @Test
    void recentlySentMailStays() {
        var id = outbox(daysAgo(10), daysAgo(3), null);
        purger.purge();
        assertEquals(1L, count(id));
    }

    @Test
    void deadMailOlderThanThirtyDaysGoes() {
        var id = outbox(daysAgo(31), null, null); // next_attempt_at null = dead
        purger.purge();
        assertEquals(0L, count(id));
    }

    /** The brief only tests the old dead row; a dead row is aged from createdAt just like a sent
     * one, so a fresh one must survive exactly as a fresh sent row does. */
    @Test
    void deadMailYoungerThanThirtyDaysStays() {
        var id = outbox(daysAgo(5), null, null); // dead, but only 5 days old
        purger.purge();
        assertEquals(1L, count(id), "a dead row inside the 30-day inspection window must survive");
    }

    @Test
    void mailStillInItsRetryWindowIsNeverTouched() {
        var id = outbox(daysAgo(60), null, Instant.now().plusSeconds(60));
        purger.purge();
        assertEquals(1L, count(id), "a row still due for retry must survive regardless of age");
    }

    @Test
    void expiredAuthTokensGoADayAfterTheyDie() {
        Long fresh = TokenFixtures.seedResetToken(Instant.now().plusSeconds(3600));
        Long stale = TokenFixtures.seedResetToken(daysAgo(2));
        purger.purge();
        assertEquals(1L, TokenFixtures.countResetToken(fresh));
        assertEquals(0L, TokenFixtures.countResetToken(stale));
    }

    /** The purger deletes both auth-token tables; login tickets get their own pass since they are
     * a distinct entity from password-reset tokens, not just another row shape of the same one. */
    @Test
    void expiredLoginTicketsGoADayAfterTheyDie() {
        Long fresh = TokenFixtures.seedLoginTicket(Instant.now().plusSeconds(3600));
        Long stale = TokenFixtures.seedLoginTicket(daysAgo(2));
        purger.purge();
        assertEquals(1L, TokenFixtures.countLoginTicket(fresh));
        assertEquals(0L, TokenFixtures.countLoginTicket(stale));
    }
}
