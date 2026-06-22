package site.asm0dey.calit.scheduler;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.email.EmailService;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Feature 14: auto-expire approval-mode (PENDING) bookings whose hold window has
 * elapsed. Runs on EVERY replica every 60s. Multi-node-safe with NO leader: each
 * tick claims expired PENDING rows with SELECT ... FOR UPDATE SKIP LOCKED, so two
 * replicas never decline the same booking. Flipping PENDING -> DECLINED drops the
 * row from the booking_no_overlap_held guard (partial on PENDING/CONFIRMED),
 * which immediately frees the held slot for a new booking.
 */
@ApplicationScoped
public class PendingExpiryScheduler {

    @ConfigProperty(name = "calit.approval.hold-hours", defaultValue = "24")
    int holdHours;

    @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30")
    int graceSeconds;

    @Inject
    EntityManager em;

    @Inject
    EmailService emailService;

    /**
     * Feature 14 expiry tick. Runs on EVERY replica every 60s, leaderless (FOR UPDATE SKIP LOCKED).
     * Per expired booking, in the SAME transaction as the claim: flip PENDING -> DECLINED, drop its
     * unsent reminder, and enqueue the declined email to the outbox (crash-safe). OutboxScheduler
     * delivers it. A node dying mid-tick loses nothing: all three commit together or not at all.
     */
    @Scheduled(every = "60s")
    void expirePendingBookings() {
        claimAndDeclineExpired();
    }

    /**
     * Claims expired PENDING bookings FOR UPDATE SKIP LOCKED and, per booking in the SAME tx:
     * flips to DECLINED, deletes any unsent reminder, enqueues the declined email. Expiry =
     * min(createdAt + holdHours, startUtc) <= now() + grace. A render failure for one poison
     * booking is caught/logged so it can't roll back the whole batch.
     */
    void claimAndDeclineExpired() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM booking "
                            + "WHERE status = 'PENDING' "
                            + "AND LEAST(created_at + (:holdHours * INTERVAL '1 hour'), start_utc) "
                            + "    <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY created_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("holdHours", holdHours)
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            for (Number n : ids) {
                Long id = n.longValue();
                Booking b = Booking.findById(id);
                b.status = BookingStatus.DECLINED;   // flipped within the lock-holding transaction
                Reminder.deleteUnsentFor(id);        // was ReminderScheduler.onDeclined observer
                // Guard covers a render/load failure (throws before any persist): the flip + cleanup
                // still commit, one mail dropped. A crash (not caught) rolls back the whole tx pre-commit
                // and the row is reclaimed next tick -- the crash-safety guarantee.
                try {
                    emailService.enqueueDeclined(id); // was EmailService.onDeclined observer; durable, same tx
                } catch (Exception ex) {
                    Log.errorf(ex, "declined enqueue failed for booking %d (declined, mail dropped)", id);
                }
            }
        });
    }
}
