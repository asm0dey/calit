package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.user.LoginTicket;
import site.asm0dey.calit.user.PasswordResetToken;

/**
 * Data nothing should have been keeping. Not configurable, and deliberately so: there is no
 * deployment for which holding the rendered HTML of a year-old booking mail, complete with the
 * recipient's address, is the right answer.
 *
 * <p>Runs on EVERY replica, daily, with no leader and no row-claim locks: unlike {@link
 * ReminderScheduler} / {@link RetentionScheduler} / {@code OutboxScheduler}, this scheduler never
 * needs {@code SELECT ... FOR UPDATE SKIP LOCKED}. A plain {@code DELETE ... WHERE} is naturally
 * safe under concurrent execution — two replicas racing the same tick each delete only the rows
 * still matching their own predicate; a row deleted by one is simply gone for the other (0 rows
 * affected there), never double-deleted or corrupted. There is nothing to claim because there is
 * nothing to hand back on failure — a purge is not a piece of work a row can be "returned" from.
 *
 * <p>ponytail: two hardcoded constants, documented in the config reference. Add env vars only if
 * an operator actually asks.
 */
@ApplicationScoped
public class PurgeScheduler {
    /**
     * How long a delivered or dead mail is kept for operational inspection before it goes.
     */
    private static final Duration MAIL_RETENTION = Duration.ofDays(30);
    /**
     * Grace past an auth token's own expiry, so a just-expired link still explains itself.
     */
    private static final Duration TOKEN_GRACE = Duration.ofDays(1);

    @Scheduled(cron = "0 37 3 * * ?")
    void dailyPurge() {
        purge();
    }

    /**
     * One pass. A row still inside its retry window ({@code next_attempt_at IS NOT NULL} and
     * unsent) is never touched regardless of age — dropping it would lose a mail calit still
     * intends to deliver. Sent {@code reminder} rows are deliberately left alone here: a booking
     * id and a timestamp, no personal data, and they cascade away with the booking.
     */
    @Transactional
    void purge() {
        var mailCutoff = Instant.now().minus(MAIL_RETENTION);
        long sent = EmailOutbox.delete("sentAt is not null and sentAt < ?1", mailCutoff);
        long dead = EmailOutbox.delete("sentAt is null and nextAttemptAt is null and createdAt < ?1", mailCutoff);

        var tokenCutoff = Instant.now().minus(TOKEN_GRACE);
        long resets = PasswordResetToken.delete("expiresAt < ?1", tokenCutoff);
        long tickets = LoginTicket.delete("expiresAt < ?1", tokenCutoff);

        if (sent + dead + resets + tickets > 0) {
            Log.infof(
                    "PRIVACY purge: outbox sent=%d dead=%d, reset-tokens=%d, login-tickets=%d",
                    sent,
                    dead,
                    resets,
                    tickets
            );
        }
    }
}
