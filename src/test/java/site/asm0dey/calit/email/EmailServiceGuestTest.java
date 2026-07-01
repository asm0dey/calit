package site.asm0dey.calit.email;

import static org.junit.jupiter.api.Assertions.*;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class EmailServiceGuestTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";
    private static final String GUEST_EMAIL = "guest@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        // Google connected: invitee/owner suppression must NOT affect guests (they always get calit mail).
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        QuarkusTransaction.requiringNew().run(() -> {
            BookingGuest.deleteAll();
            Booking.deleteAll();
        });
    }

    /** Seeds a CONFIRMED booking (icsSequence 0) with one guest of the given status; returns the booking id. */
    private long seedWithGuest(GuestStatus guestStatus) {
        return seedWithGuest(guestStatus, BookingStatus.CONFIRMED, 0);
    }

    /** Seeds a booking with the given status + icsSequence and one guest; returns the booking id. */
    private long seedWithGuest(GuestStatus guestStatus, BookingStatus bookingStatus, int icsSequence) {
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
            t.name = "Discovery Call";
            t.slug = "disc-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.persist();
            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
            b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
            b.meetLink = "https://meet.google.com/abc";
            b.status = bookingStatus;
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            b.icsSequence = icsSequence;
            b.persist();
            BookingGuest g = new BookingGuest();
            g.ownerId = 1L;
            g.bookingId = b.id;
            g.email = GUEST_EMAIL;
            g.status = guestStatus;
            g.declineToken = "dt-" + System.nanoTime();
            g.createdAt = Instant.now();
            g.persist();
            return b.id;
        });
    }

    @Test
    void confirmedSendsGuestDeclineLinkNoIcsWhenGoogleConnected() {
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST_EMAIL);
        assertEquals(1, toGuest.size(), "guest always gets a calit link email");
        Mail m = toGuest.getFirst();
        assertTrue(m.getHtml().contains("/guest/"), "guest decline link present");
        assertTrue(m.getHtml().contains("/decline"), "guest decline link suffix");
        assertFalse(m.getHtml().contains("/manage"), "guest must NOT get a manage/reschedule link");
        assertTrue(m.getAttachments().isEmpty(), "no .ics when Google connected (Google sends the invite)");
    }

    @Test
    void confirmedSendsGuestIcsWhenGoogleDisconnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail m = mailbox.getMailsSentTo(GUEST_EMAIL).getFirst();
        assertTrue(m.getHtml().contains("/decline"), "guest decline link present");
        assertFalse(m.getAttachments().isEmpty(), "guest .ics attached when Google is the only calendar source");
    }

    @Test
    void approvedSendsGuestDeclineLinkNoIcsWhenGoogleConnected() {
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleApproved(new BookingApproved(bookingId));

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST_EMAIL);
        assertEquals(1, toGuest.size(), "guest gets an invite on approval");
        Mail m = toGuest.getFirst();
        assertTrue(m.getHtml().contains("/guest/"), "guest decline link present");
        assertTrue(m.getHtml().contains("/decline"), "guest decline link suffix");
        assertFalse(m.getHtml().contains("/manage"), "guest must NOT get a manage/reschedule link");
        assertTrue(m.getAttachments().isEmpty(), "no .ics when Google connected (Google sends the invite)");
        // Approved guests reuse the confirmed subject by spec.
        assertTrue(m.getSubject().toLowerCase().contains("confirmed"), "approved guest reuses confirmed subject");
    }

    @Test
    void rescheduledSendsGuestDeclineLinkNoIcsWhenGoogleConnected() {
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleRescheduled(new BookingRescheduled(bookingId, Instant.parse("2026-06-07T09:00:00Z"), false));

        List<Mail> toGuest = mailbox.getMailsSentTo(GUEST_EMAIL);
        assertEquals(1, toGuest.size(), "guest gets an updated invite on reschedule");
        Mail m = toGuest.getFirst();
        assertTrue(m.getAttachments().isEmpty(), "no .ics when Google connected (Google sends the update)");
        assertTrue(m.getSubject().toLowerCase().contains("reschedul"), "reschedule subject");
        assertTrue(m.getHtml().contains("/decline"), "guest decline link present");
        assertFalse(m.getHtml().contains("/manage"), "guest must NOT get a manage/reschedule link");
    }

    @Test
    void cancelledSendsGuestCancel() {
        long bookingId = seedWithGuest(GuestStatus.INVITED);

        emailService.handleCancelled(new BookingCancelled(bookingId));

        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "active guest gets a cancellation");
        Mail guestMail = mailbox.getMailsSentTo(GUEST_EMAIL).getFirst();
        assertTrue(guestMail.getSubject().toLowerCase().contains("cancel"));
        assertTrue(guestMail.getAttachments().isEmpty(), "no .ics when Google connected (Google cancels natively)");
    }

    @Test
    void declinedGuestStatusGetsNoInviteOnConfirmed() {
        long bookingId = seedWithGuest(GuestStatus.DECLINED);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertTrue(mailbox.getMailsSentTo(GUEST_EMAIL).isEmpty(), "declined guest is not an active recipient");
    }

    @Test
    void guestDeclinedNotifiesInviteeAndCancelsThatGuest() {
        long bookingId = seedWithGuest(GuestStatus.DECLINED); // already declined in the DB
        Long guestId = BookingGuest.<BookingGuest>find("bookingId", bookingId).firstResult().id;

        emailService.handleGuestDeclined(new GuestDeclined(bookingId, guestId));

        // Guest gets a cancel notice; invitee gets a "guest declined, you may want to reschedule" notice.
        // Google connected -> Google natively cancels the removed guest, so calit attaches no .ics.
        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "departing guest gets a cancel notice");
        assertTrue(
                mailbox.getMailsSentTo(GUEST_EMAIL).getFirst().getAttachments().isEmpty(),
                "no .ics when Google connected (Google cancels the removed attendee)");
        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        assertEquals(1, toInvitee.size(), "invitee notified of the decline");
        assertTrue(toInvitee.getFirst().getHtml().contains("/manage"), "invitee notice links to reschedule");
    }

    @Test
    void guestRemovedSendsCancelToThatGuestOnly() {
        long bookingId = seedWithGuest(GuestStatus.REMOVED);
        Long guestId = BookingGuest.<BookingGuest>find("bookingId", bookingId).firstResult().id;

        emailService.handleGuestRemoved(new GuestRemoved(bookingId, guestId));

        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "removed guest gets a cancel");
        assertTrue(mailbox.getMailsSentTo(INVITEE_EMAIL).isEmpty(), "invitee initiated removal — not notified");
    }

    @Test
    void declinedAfterRescheduleSendsGuestCancelToInvitedGuest() {
        // Confirmed/approved (guests invited) then rescheduled back to PENDING -> icsSequence>0.
        long bookingId = seedWithGuest(GuestStatus.INVITED, BookingStatus.PENDING, 1);

        emailService.handleDeclined(new BookingDeclined(bookingId));

        assertEquals(1, mailbox.getMailsSentTo(GUEST_EMAIL).size(), "previously-invited guest gets a cancel");
        assertTrue(mailbox.getMailsSentTo(GUEST_EMAIL)
                .getFirst()
                .getSubject()
                .toLowerCase()
                .contains("cancel"));
    }

    @Test
    void declinedNeverConfirmedSendsNoGuestMail() {
        // Never confirmed -> icsSequence==0 -> guests never received an invite -> no cancel.
        long bookingId = seedWithGuest(GuestStatus.INVITED, BookingStatus.PENDING, 0);

        emailService.handleDeclined(new BookingDeclined(bookingId));

        assertTrue(mailbox.getMailsSentTo(GUEST_EMAIL).isEmpty(), "never-invited guest gets no cancel");
    }
}
