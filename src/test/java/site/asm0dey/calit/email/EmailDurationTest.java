package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

/**
 * A booking's confirmation must print the booking's OWN length, not the meeting type's default
 * duration. A 120-minute booking made on a 30-minute-default type must announce itself as "120
 * minutes" -- never "30 minutes".
 */
@QuarkusTest
class EmailDurationTest {

    private static final String OWNER_EMAIL = "owner-dur@example.com";
    private static final String INVITEE_EMAIL = "invitee-dur@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        QuarkusTransaction.requiringNew().run(() -> Booking.deleteAll());
    }

    /** Type defaults to 30 minutes; this booking was made at 120. */
    private long seed() {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "UTC";
            s.locale = "en";
            s.ownerNotificationsEnabled = true;
            s.timeFormat = "auto";
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Duration Call";
            t.slug = "duration-call-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.PHONE;
            t.locationDetail = "+1 555 0100";
            t.persist();

            var start = Instant.parse("2026-06-08T13:00:00Z");
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = start;
            b.endUtc = start.plus(120, ChronoUnit.MINUTES);
            b.status = BookingStatus.CONFIRMED;
            b.manageToken = UUID.randomUUID().toString();
            b.createdAt = Instant.now();
            b.answers = Map.of();
            b.locale = "en";
            b.persist();
            return b.id;
        });
    }

    @Test
    void confirmationShowsTheBookedLengthNotTheTypeDefault() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed();

        emailService.handleConfirmed(new BookingConfirmed(id));

        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toOwner.size(), "host must receive their copy");
        String hostHtml = toOwner.getFirst().getHtml();
        assertTrue(hostHtml.contains("120 minutes"), "host copy must show the booked 120 minutes; got: " + hostHtml);
        assertFalse(hostHtml.contains("30 minutes"), "host copy must not show the type's default; got: " + hostHtml);

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive confirmation");
        String inviteeHtml = toInvitee.getFirst().getHtml();
        assertTrue(
                inviteeHtml.contains("120 minutes"),
                "invitee copy must show the booked 120 minutes; got: " + inviteeHtml);
        assertFalse(
                inviteeHtml.contains("30 minutes"),
                "invitee copy must not show the type's default; got: " + inviteeHtml);
    }
}
