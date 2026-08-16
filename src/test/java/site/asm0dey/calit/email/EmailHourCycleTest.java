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
 * A host who picks h12 gets AM/PM in their OWN copy; the invitee's copy is unaffected by the
 * host's preference (a booking page must not carry one person's clock convention), and "auto"
 * leaves the translated pattern alone so upgrading changes nothing.
 */
@QuarkusTest
class EmailHourCycleTest {

    private static final String OWNER_EMAIL = "owner-hc@example.com";
    private static final String INVITEE_EMAIL = "invitee-hc@example.com";

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

    /** 13:00 UTC is 13:00 in UTC — 24h renders "13:00", 12h renders "1:00 PM". */
    private long seed(String hostTimeFormat) {
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
            s.timeFormat = hostTimeFormat;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "HC Call";
            t.slug = "hc-call-" + System.nanoTime();
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
            b.endUtc = start.plus(30, ChronoUnit.MINUTES);
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
    void hostCopyUsesTwelveHourWhenTheHostChoseIt() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed("h12");

        emailService.handleConfirmed(new BookingConfirmed(id));

        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toOwner.size(), "host must receive their copy");
        String html = toOwner.getFirst().getHtml();
        assertTrue(html.contains("1:00 PM"), "host copy must be 12-hour; got: " + html);
    }

    @Test
    void inviteeCopyIgnoresTheHostPreference() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed("h12");

        emailService.handleConfirmed(new BookingConfirmed(id));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee must receive confirmation");
        String html = toInvitee.getFirst().getHtml();
        assertTrue(html.contains("13:00"), "invitee copy must keep the translated 24h pattern; got: " + html);
        assertFalse(html.contains("1:00 PM"), "host preference must not leak to the invitee");
    }

    @Test
    void autoLeavesTheTranslatedPatternAlone() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed("auto");

        emailService.handleConfirmed(new BookingConfirmed(id));

        String html = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getHtml();
        assertTrue(html.contains("13:00"), "auto must reproduce today's 24h output; got: " + html);
    }

    @Test
    void explicitH23StaysTwentyFourHourAndNeverFlipsToAmPm() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed("h23");

        emailService.handleConfirmed(new BookingConfirmed(id));

        String html = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst().getHtml();
        assertTrue(html.contains("13:00"), "h23 must render 24-hour; got: " + html);
        assertFalse(html.contains("1:00 PM"), "h23's entire purpose is never AM/PM; got: " + html);
    }
}
