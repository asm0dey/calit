package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class ReminderTickTest {

    @Inject
    ReminderScheduler scheduler;

    @Test
    void dispatchMarksOnlyDueUnsentReminders() {
        // reminder.booking_id REFERENCES booking(id): seed a real booking to attach reminders to
        // (Plan 6 deviation -- the plan used bookingId=1L which has no booking row -> FK violation).
        Long bookingId = seedBooking();
        Long dueId    = persistReminder(bookingId, Instant.now().minus(1, ChronoUnit.MINUTES), null);  // due, unsent
        Long futureId = persistReminder(bookingId, Instant.now().plus(1, ChronoUnit.HOURS), null);     // not due yet
        Long sentId   = persistReminder(bookingId, Instant.now().minus(1, ChronoUnit.HOURS),
                                        Instant.now().minus(30, ChronoUnit.MINUTES));                   // already sent

        scheduler.dispatchDueReminders();

        // Only the due+unsent reminder is now marked sent.
        assertNotNull(reloadSentAt(dueId),    "due reminder must be marked sent");
        assertNull(reloadSentAt(futureId),    "not-yet-due reminder must stay unsent");
        assertNotNull(reloadSentAt(sentId),   "already-sent reminder is untouched (still sent)");
    }

    private Long seedBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "tick-" + System.nanoTime();
            t.slug = "tick-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            Instant start = Instant.now().plus(200, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plusSeconds(1800);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private Long persistReminder(Long bookingId, Instant sendAt, Instant sentAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = sendAt;
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = sentAt;
            r.persist();
            return r.id;
        });
    }

    private Instant reloadSentAt(Long id) {
        return QuarkusTransaction.requiringNew()
                .call(() -> ((Reminder) Reminder.findById(id)).sentAt);
    }
}
