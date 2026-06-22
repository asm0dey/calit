package site.asm0dey.calit.scheduler;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.events.BookingApproved;
import site.asm0dey.calit.booking.events.BookingCancelled;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.booking.events.BookingDeclined;
import site.asm0dey.calit.booking.events.BookingRequested;
import site.asm0dey.calit.booking.events.BookingRescheduled;
import site.asm0dey.calit.email.EmailService;
import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@ApplicationScoped
public class ReminderScheduler {

    @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "1440")
    int leadMinutes;

    @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30")
    int graceSeconds;

    @Inject
    EntityManager em;

    @Inject
    EmailService emailService;

    // --- lifecycle observers (creation/recompute/delete side) ---

    /** Auto-confirmed at book time. */
    void onConfirmed(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingConfirmed e) {
        scheduleReminder(e.bookingId());
    }

    /** PENDING -> CONFIRMED via owner approval. */
    void onApproved(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingApproved e) {
        scheduleReminder(e.bookingId());
    }

    /** Cancelled by invitee. */
    void onCancelled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingCancelled e) {
        onCancelledOrDeclined(e.bookingId());
    }

    /**
     * A booking (re-)entered the approval queue as PENDING — either a fresh approval-type request or
     * an approval-type reschedule (Plan 3 re-fires BookingRequested, NOT BookingRescheduled). A PENDING
     * booking must hold no reminder, so drop any unsent one left over from a prior CONFIRMED state;
     * the eventual BookingApproved reschedules it at the new time.
     */
    void onRequested(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRequested e) {
        onCancelledOrDeclined(e.bookingId());
    }

    /** Declined by owner OR auto-expired (Plan 6 expiry tick fires BookingDeclined). */
    void onDeclined(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingDeclined e) {
        onCancelledOrDeclined(e.bookingId());
    }

    /** Auto-type reschedule stays CONFIRMED at a new time: recompute the reminder. */
    void onRescheduled(@Observes(during = TransactionPhase.AFTER_SUCCESS) BookingRescheduled e) {
        scheduleReminder(e.bookingId());
    }

    /**
     * Create (or replace) the unsent reminder for a now-CONFIRMED booking at
     * (startUtc - leadMinutes). Skips if that instant is already in the past.
     * Opens its own transaction (AFTER_SUCCESS observers have no active one).
     */
    public void scheduleReminder(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking booking = Booking.findById(bookingId);
            if (booking == null) {
                return;
            }
            Instant sendAt = booking.startUtc.minus(leadMinutes, ChronoUnit.MINUTES);
            // Booking made inside the lead window: nothing to remind about ahead of time.
            if (!sendAt.isAfter(Instant.now())) {
                return;
            }
            // Never double-schedule (re-confirm / reschedule recompute).
            Reminder.deleteUnsentFor(bookingId);
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = sendAt;
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
        });
    }

    /** Drop the future unsent reminder for a cancelled/declined/expired booking. */
    public void onCancelledOrDeclined(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> Reminder.deleteUnsentFor(bookingId));
    }

    /**
     * Feature 15 dispatch tick. Runs on EVERY replica every 60s. Multi-node-safe with NO leader:
     * each tick claims due unsent reminders with SELECT ... FOR UPDATE SKIP LOCKED. The reminder
     * email is enqueued to the outbox in the SAME transaction as the claim (crash-safe), so a node
     * dying mid-tick never loses a reminder: either both the sent_at stamp and the outbox row commit,
     * or neither does and the row is reclaimed next tick. OutboxScheduler delivers, with retry/backoff.
     */
    @Scheduled(every = "60s")
    void dispatchDueReminders() {
        claimAndMarkDueReminders();
    }

    /**
     * Claims up to 50 due unsent reminders FOR UPDATE SKIP LOCKED, and for each, in the SAME tx:
     * stamps sent_at (exactly-once claim) and enqueues the reminder email to the outbox. A render
     * failure for one poison booking is caught and logged so it can't roll back the whole batch.
     */
    void claimAndMarkDueReminders() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM reminder "
                            + "WHERE sent_at IS NULL AND send_at <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY send_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            Instant now = Instant.now();
            for (Number n : ids) {
                Reminder r = Reminder.findById(n.longValue());
                r.sentAt = now; // claim: marked within the lock-holding transaction
                // Guard covers a render/load failure (e.g. missing OwnerSettings), which throws
                // BEFORE any persist -- session stays clean, the claim still commits, one mail dropped.
                // A node crash is not caught here: it kills the process pre-commit, the tx rolls back,
                // and the row is reclaimed next tick -- that is the crash-safety guarantee.
                try {
                    emailService.enqueueReminder(r.bookingId); // durable intent, same tx
                } catch (Exception ex) {
                    Log.errorf(ex, "reminder enqueue failed for booking %d (marked sent, mail dropped)",
                            r.bookingId);
                }
            }
        });
    }
}
