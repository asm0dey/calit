package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;

@QuarkusTest
@TestProfile(ReminderGraceWindowTest.Grace120Profile.class)
class ReminderGraceWindowTest {

    public static class Grace120Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("calit.scheduler.grace-seconds", "120");
        }
    }

    @Inject
    ReminderScheduler scheduler;

    @Test
    void dispatchSendsRemindersDueWithinGraceWindow() {
        var bookingId = seedBooking();
        var withinGrace =
                persistReminder(bookingId, Instant.now().plus(60, ChronoUnit.SECONDS)); // +60s, grace=120s -> due
        var beyondGrace = persistReminder(bookingId, Instant.now().plus(1, ChronoUnit.HOURS)); // +1h -> not due

        scheduler.dispatchDueReminders();

        assertNotNull(reloadSentAt(withinGrace), "reminder due within the grace window must be marked sent");
        assertNull(reloadSentAt(beyondGrace), "reminder beyond the grace window must stay unsent");
    }

    private Long seedBooking() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "grace-" + System.nanoTime();
            t.slug = "grace-" + System.nanoTime();
            t.durationMinutes = 30;
            t.persist();
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam";
            b.inviteeEmail = "sam@example.com";
            var start = Instant.now().plus(200, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plusSeconds(1800);
            b.status = BookingStatus.CONFIRMED;
            b.createdAt = Instant.now();
            b.manageToken = UUID.randomUUID().toString();
            b.persist();
            return b.id;
        });
    }

    private Long persistReminder(Long bookingId, Instant sendAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = sendAt;
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
            return r.id;
        });
    }

    private Instant reloadSentAt(Long id) {
        return QuarkusTransaction.requiringNew().call(() -> ((Reminder) Reminder.findById(id)).sentAt);
    }
}
