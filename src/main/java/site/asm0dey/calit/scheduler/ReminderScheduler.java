package site.asm0dey.calit.scheduler;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.notify.NotificationDispatcher;

@ApplicationScoped
public class ReminderScheduler {

    final int leadMinutes;

    final int graceSeconds;

    final EntityManager em;

    final EmailService emailService;

    final NotificationDispatcher channels;

    @Inject
    public ReminderScheduler(
            EntityManager em,
            EmailService emailService,
            NotificationDispatcher channels,
            @ConfigProperty(name = "calit.reminder.lead-minutes", defaultValue = "1440") int leadMinutes,
            @ConfigProperty(name = "calit.scheduler.grace-seconds", defaultValue = "30") int graceSeconds) {
        this.em = em;
        this.emailService = emailService;
        this.channels = channels;
        this.leadMinutes = leadMinutes;
        this.graceSeconds = graceSeconds;
    }

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
            // Multi-host: a reminder belongs to the group's lead row only -- never N reminders for
            // one conceptual meeting. BookingConfirmed/BookingApproved/BookingRescheduled already
            // fire once with the lead id (Tasks 9-11), so this is normally a no-op guard.
            if (booking.groupId != null) {
                Long creatorOwnerId = MeetingType.<MeetingType>findById(booking.meetingTypeId).ownerId;
                Booking lead = Booking.leadOfGroup(booking.groupId, creatorOwnerId);
                if (!lead.id.equals(booking.id)) {
                    return;
                }
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
     *
     * <p>Outbound channel notifications (Telegram/Slack/ntfy/...) are dispatched AFTER that
     * transaction commits, never inside it: a reminder has no CDI event to observe (the outbox
     * enqueue above replaced it), so the dispatch is explicit -- and it must not be able to roll
     * back the claim, delay it, or cost the reminder email when a channel is broken.
     */
    void claimAndMarkDueReminders() {
        List<Long> claimed = new ArrayList<>();
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery("SELECT id FROM reminder "
                            + "WHERE sent_at IS NULL AND send_at <= now() + make_interval(secs => :graceSeconds) "
                            + "ORDER BY send_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .setParameter("graceSeconds", (double) graceSeconds)
                    .getResultList();

            var now = Instant.now();
            for (Number n : ids) {
                Reminder r = Reminder.findById(n.longValue());
                r.sentAt = now; // claim: marked within the lock-holding transaction
                claimed.add(r.bookingId);
                // Guard covers a render/load failure (e.g. missing OwnerSettings), which throws
                // BEFORE any persist -- session stays clean, the claim still commits, one mail dropped.
                // A node crash is not caught here: it kills the process pre-commit, the tx rolls back,
                // and the row is reclaimed next tick -- that is the crash-safety guarantee.
                try {
                    emailService.enqueueReminder(r.bookingId); // durable intent, same tx
                } catch (Exception ex) {
                    Log.errorf(ex, "reminder enqueue failed for booking %d (marked sent, mail dropped)", r.bookingId);
                }
            }
        });
        // requiringNew().run() propagates a commit failure, so nothing below runs unless the claims
        // are durable -- no channel message for a reminder that will be reclaimed next tick.
        for (Long bookingId : claimed) {
            channels.notifyReminder(bookingId); // swallows its own failures
        }
    }
}
