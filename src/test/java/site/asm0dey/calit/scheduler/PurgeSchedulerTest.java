package site.asm0dey.calit.scheduler;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
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
        return QuarkusTransaction
            .requiringNew()
            .call(() -> EmailOutbox.count("id", id));
    }

    private static Instant daysAgo(int d) {
        return Instant.now().minus(d, ChronoUnit.DAYS);
    }

    @Test
    void sentMailOlderThanThirtyDaysGoes() {
        var id = outbox(daysAgo(40), daysAgo(31), null);
        purger.purge();
        assertThat(count(id)).isZero();
    }

    @Test
    void recentlySentMailStays() {
        var id = outbox(daysAgo(10), daysAgo(3), null);
        purger.purge();
        assertThat(count(id)).isOne();
    }

    @Test
    void deadMailOlderThanThirtyDaysGoes() {
        // next_attempt_at null = dead
        var id = outbox(daysAgo(31), null, null);
        purger.purge();
        assertThat(count(id)).isZero();
    }

    /**
     * The brief only tests the old dead row; a dead row is aged from createdAt just like a sent
     * one, so a fresh one must survive exactly as a fresh sent row does.
     */
    @Test
    void deadMailYoungerThanThirtyDaysStays() {
        // dead, but only 5 days old
        var id = outbox(daysAgo(5), null, null);
        purger.purge();
        assertThat(count(id)).as("a dead row inside the 30-day inspection window must survive").isOne();
    }

    @Test
    void mailStillInItsRetryWindowIsNeverTouched() {
        var id = outbox(daysAgo(60), null, Instant.now().plusSeconds(60));
        purger.purge();
        assertThat(count(id)).as("a row still due for retry must survive regardless of age").isOne();
    }

    @Test
    void expiredAuthTokensGoADayAfterTheyDie() {
        Long fresh = TokenFixtures.seedResetToken(Instant.now().plusSeconds(3600));
        Long stale = TokenFixtures.seedResetToken(daysAgo(2));
        purger.purge();
        assertThat(TokenFixtures.countResetToken(fresh)).isOne();
        assertThat(TokenFixtures.countResetToken(stale)).isZero();
    }

    /**
     * The purger deletes both auth-token tables; login tickets get their own pass since they are
     * a distinct entity from password-reset tokens, not just another row shape of the same one.
     */
    @Test
    void expiredLoginTicketsGoADayAfterTheyDie() {
        Long fresh = TokenFixtures.seedLoginTicket(Instant.now().plusSeconds(3600));
        Long stale = TokenFixtures.seedLoginTicket(daysAgo(2));
        purger.purge();
        assertThat(TokenFixtures.countLoginTicket(fresh)).isOne();
        assertThat(TokenFixtures.countLoginTicket(stale)).isZero();
    }
}
