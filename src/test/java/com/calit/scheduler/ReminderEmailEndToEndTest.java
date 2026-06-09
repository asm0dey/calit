package com.calit.scheduler;

import com.calit.booking.Booking;
import com.calit.booking.BookingStatus;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import com.calit.google.CalendarPort;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * End-to-end check that the dispatch tick's out-of-claim-transaction ReminderDue fire is
 * actually delivered to Plan 4's {@code @Observes(during = AFTER_SUCCESS)} reminder observer,
 * which then sends BOTH the invitee (Google disconnected -> fallback) and owner (notifications
 * enabled) reminder emails. Guards correction #3: an AFTER_SUCCESS observer fired with NO active
 * transaction may not be delivered, so the tick fires each event inside its own committed tx.
 */
@QuarkusTest
class ReminderEmailEndToEndTest {

    private static final String OWNER_EMAIL = "owner-e2e@example.com";
    private static final String INVITEE_EMAIL = "invitee-e2e@example.com";

    @Inject
    ReminderScheduler scheduler;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void dispatchTickDeliversReminderEmailToInviteeAndOwner() {
        // Google disconnected -> invitee fallback reminder fires.
        when(calendarPort.isConnected()).thenReturn(false);

        Long bookingId = seedConfirmedBookingWithOwner();
        seedDueUnsentReminder(bookingId);

        mailbox.clear();

        scheduler.dispatchDueReminders();

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size(),
                "invitee reminder must arrive (Google disconnected -> fallback)");
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size(),
                "owner reminder must arrive (ownerNotificationsEnabled=true)");
        assertTrue(mailbox.getMailsSentTo(INVITEE_EMAIL).get(0).getSubject()
                .toLowerCase().contains("reminder"), "subject identifies the reminder email");

        // Cleanup committed rows so they don't leak to other tests.
        QuarkusTransaction.requiringNew().run(() -> {
            Reminder.delete("bookingId", bookingId);
            Booking.deleteById(bookingId);
        });
    }

    private Long seedConfirmedBookingWithOwner() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.get();
            if (s == null) {
                s = new OwnerSettings();
                s.id = OwnerSettings.SINGLETON_ID;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.name = "E2E Reminder Call";
            t.slug = "e2e-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0123";
            t.persist();

            Booking b = new Booking();
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            // A unique far-future slot so the booking_no_overlap_held guard never collides.
            Instant start = Instant.now().plus(500, ChronoUnit.HOURS);
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
            r.sentAt = null;                                       // unsent
            r.persist();
        });
    }
}
