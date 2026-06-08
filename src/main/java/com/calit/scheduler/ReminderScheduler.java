package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.events.BookingApproved;
import com.calit.booking.events.BookingCancelled;
import com.calit.booking.events.BookingConfirmed;
import com.calit.booking.events.BookingDeclined;
import com.calit.booking.events.BookingRescheduled;
import com.calit.booking.events.ReminderDue;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ReminderScheduler {

    @ConfigProperty(name = "calit.reminders.lead-minutes", defaultValue = "1440")
    int leadMinutes;

    @Inject
    EntityManager em;

    @Inject
    Event<ReminderDue> reminderDueEvent;

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
     * Feature 15 dispatch tick. Runs on EVERY replica every 60s. Multi-node-safe with NO
     * leader: each tick claims due unsent reminders with SELECT ... FOR UPDATE SKIP LOCKED,
     * so a concurrent replica's identical query skips rows this one already locked. Two
     * replicas never claim the same reminder -> each reminder is sent exactly once.
     */
    @Scheduled(every = "60s")
    void dispatchDueReminders() {
        List<Long> bookingIds = claimAndMarkDueReminders();
        // Fire AFTER the claim transaction commits (rows are durably marked sent). Each fire
        // runs in its own committed transaction so the AFTER_SUCCESS email observer is delivered
        // (an out-of-transaction AFTER_SUCCESS fire is NOT delivered by Quarkus ArC). Plan 6 deviation.
        for (Long bookingId : bookingIds) {
            QuarkusTransaction.requiringNew().run(() -> reminderDueEvent.fire(new ReminderDue(bookingId)));
        }
    }

    /**
     * Claims up to 50 due unsent reminders FOR UPDATE SKIP LOCKED, stamps sent_at = now()
     * on each in the SAME transaction (atomic claim+mark), and returns their bookingIds.
     */
    List<Long> claimAndMarkDueReminders() {
        return QuarkusTransaction.requiringNew().call(() -> {
            @SuppressWarnings("unchecked")
            List<Number> ids = em.createNativeQuery(
                    "SELECT id FROM reminder "
                            + "WHERE sent_at IS NULL AND send_at <= now() "
                            + "ORDER BY send_at "
                            + "FOR UPDATE SKIP LOCKED "
                            + "LIMIT 50")
                    .getResultList();

            List<Long> bookingIds = new ArrayList<>();
            Instant now = Instant.now();
            for (Number n : ids) {
                Long id = n.longValue();
                Reminder r = Reminder.findById(id);
                r.sentAt = now;          // marked within the lock-holding transaction
                bookingIds.add(r.bookingId);
            }
            return bookingIds;
        });
    }
}
