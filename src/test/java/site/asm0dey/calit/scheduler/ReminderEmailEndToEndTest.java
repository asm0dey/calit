package site.asm0dey.calit.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.google.CalendarPort;

/**
 * The dispatch tick claims due reminders and, in the SAME transaction, enqueues the reminder email
 * to the outbox (crash-safe: claim + intent-to-send commit atomically). OutboxScheduler delivers it.
 * This asserts both recipients (invitee via Google-disconnected fallback + owner) get a durable
 * outbox row.
 */
@QuarkusTest
class ReminderEmailEndToEndTest {

    private static final String OWNER_EMAIL = "owner-e2e@example.com";
    private static final String INVITEE_EMAIL = "invitee-e2e@example.com";

    @Inject
    ReminderScheduler scheduler;

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void dispatchTickEnqueuesReminderForInviteeAndOwner() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false); // Google off -> invitee fallback

        var bookingId = seedConfirmedBookingWithOwner();
        seedDueUnsentReminder(bookingId);

        scheduler.dispatchDueReminders();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(
                    1,
                    EmailOutbox.count("recipient", INVITEE_EMAIL),
                    "invitee reminder enqueued (Google disconnected -> fallback)");
            assertEquals(
                    1,
                    EmailOutbox.count("recipient", OWNER_EMAIL),
                    "owner reminder enqueued (ownerNotificationsEnabled=true)");
            EmailOutbox r = EmailOutbox.find("recipient", INVITEE_EMAIL).firstResult();
            assertTrue(r.subject.toLowerCase().contains("reminder"), "subject identifies the reminder email");
        });

        QuarkusTransaction.requiringNew().run(() -> {
            Reminder.delete("bookingId", bookingId);
            Booking.deleteById(bookingId);
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", OWNER_EMAIL);
        });
    }

    private Long seedConfirmedBookingWithOwner() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "E2E Reminder Call";
            t.slug = "e2e-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0123";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            var start = Instant.now().plus(500, ChronoUnit.HOURS);
            b.startUtc = start;
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }

    private void seedDueUnsentReminder(Long bookingId) {
        QuarkusTransaction.requiringNew().run(() -> {
            Reminder r = new Reminder();
            r.bookingId = bookingId;
            r.sendAt = Instant.now().minus(1, ChronoUnit.MINUTES); // due
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null; // unsent
            r.persist();
        });
    }
}
