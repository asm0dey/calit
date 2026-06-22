package site.asm0dey.calit.scheduler;

import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailOutbox;
import site.asm0dey.calit.google.CalendarPort;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class PendingExpiryDispatchTest {

    private static final String OWNER_EMAIL = "owner-exp@example.com";
    private static final String INVITEE_EMAIL = "invitee-exp@example.com";

    @Inject
    PendingExpiryScheduler scheduler;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void reset() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
            EmailOutbox.delete("recipient", INVITEE_EMAIL);
            EmailOutbox.delete("recipient", OWNER_EMAIL);
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
        });
    }

    @Test
    void expiryDeclinesEnqueuesDeclinedEmailAndDropsReminder() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        Long bookingId = seedExpiredPendingWithReminder();

        scheduler.expirePendingBookings();

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(BookingStatus.DECLINED, ((Booking) Booking.findById(bookingId)).status,
                    "expired PENDING flips to DECLINED");
            assertEquals(1, EmailOutbox.count("recipient", INVITEE_EMAIL), "invitee declined email enqueued");
            assertEquals(1, EmailOutbox.count("recipient", OWNER_EMAIL), "owner declined email enqueued");
            assertEquals(0, Reminder.count("bookingId", bookingId), "unsent reminder removed on decline");
        });
    }

    private Long seedExpiredPendingWithReminder() {
        return QuarkusTransaction.requiringNew().call(() -> {
            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Expiry Call";
            t.slug = "exp-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
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
            b.status = BookingStatus.PENDING;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now().minus(25, ChronoUnit.HOURS); // past the 24h hold -> expire
            b.persist();

            Reminder r = new Reminder();
            r.bookingId = b.id;
            r.sendAt = Instant.now().plus(100, ChronoUnit.HOURS);
            r.kind = Reminder.KIND_REMINDER;
            r.sentAt = null;
            r.persist();
            return b.id;
        });
    }
}
