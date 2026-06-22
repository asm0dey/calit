package site.asm0dey.calit.email;

import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class EmailEnqueueTest {

    private static final String OWNER_EMAIL = "owner-enq@example.com";
    private static final String INVITEE_EMAIL = "invitee-enq@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void enqueueReminderWritesOutboxRowsAndDoesNotSendDirectly() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false); // Google off -> invitee fallback
        Long bookingId = seed();
        mailbox.clear();

        QuarkusTransaction.requiringNew().run(() -> emailService.enqueueReminder(bookingId));

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL), "invitee reminder enqueued");
            assertEquals(1, EmailOutbox.count("recipient", OWNER_EMAIL), "owner reminder enqueued");
            EmailOutbox r = EmailOutbox.find("recipient", INVITEE_EMAIL).firstResult();
            assertTrue(r.subject.toLowerCase().contains("reminder"), "subject identifies the reminder");
            assertNull(r.sentAt, "queued, not sent");
        });
        assertEquals(0, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "no direct SMTP send on the enqueue path");

        QuarkusTransaction.requiringNew().run(() -> {
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", OWNER_EMAIL);
            Booking.deleteById(bookingId);
        });
    }

    private Long seed() {
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
            t.name = "Enqueue Call";
            t.slug = "enq-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = MeetingType.LocationType.PHONE;
            t.locationDetail = "+1 555 0123";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
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
}
