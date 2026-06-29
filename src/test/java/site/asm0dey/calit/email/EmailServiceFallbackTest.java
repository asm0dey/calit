package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingDeclined;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

// When SMTP is down, a booking notification must NOT throw out of the observer -- it must land in the outbox.
@QuarkusTest
class EmailServiceFallbackTest {

    @Inject
    EmailService emailService;

    // Spy the seam so we can force the raw send to fail (Mailer is @Singleton -> @InjectMock unusable).
    @InjectSpy
    MailSender mailSender;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        QuarkusTransaction.requiringNew().run(() -> {
            Booking.deleteAll();
            EmailOutbox.deleteAll();
        });
    }

    @Test
    void declinedWithSmtpDownQueuesInsteadOfThrowing() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        doThrow(new RuntimeException("smtp down"))
                .when(mailSender)
                .sendNow(anyString(), anyString(), anyString(), any());
        var bookingId = seedDeclined();

        // Must not throw.
        emailService.handleDeclined(new BookingDeclined(bookingId));

        long queued = QuarkusTransaction.requiringNew().call(() -> EmailOutbox.count());
        // declined notifies invitee + owner -> 2 parked mails.
        assertTrue(queued >= 2, "both recipients' mail parked in outbox, got " + queued);
    }

    private long seedDeclined() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = true;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Discovery Call";
            t.slug = "discovery-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = "invitee@example.com";
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.status = BookingStatus.DECLINED;
            b.meetLink = null;
            b.answers = Map.of();
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.persist();
            return b.id;
        });
    }
}
