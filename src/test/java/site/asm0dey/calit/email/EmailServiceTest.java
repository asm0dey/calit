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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.events.*;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.BookingField.FieldType;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

@QuarkusTest
class EmailServiceTest {

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";

    @Inject
    EmailService emailService;

    @Inject
    MockMailbox mailbox;

    // Mock the Google connection state so we can drive the invitee-fallback branch
    // without a real OAuth connection.
    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void init() {
        mailbox.clear();
        // Each test seeds a HELD (PENDING/CONFIRMED) booking at the same fixed start time; the DB
        // exclusion constraint (booking_no_overlap_held) rejects overlapping HELD rows. The plan's
        // seed commits its own transaction and never rolls back, so clear prior bookings first.
        QuarkusTransaction.requiringNew().run(() -> Booking.deleteAll());
    }

    // ---- Confirmed + Google NOT connected: invitee + owner BOTH get mail ----

    @Test
    void confirmedWhenGoogleDisconnectedSendsToInviteeAndOwnerWithLocationManageLinkAnswersAndIcs() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = "https://meet.google.com/abc-defg-hij";
                    b.answers = Map.of("description", "Pricing tiers", "company", "Acme");
                },
                true,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        List<Mail> toInvitee = mailbox.getMailsSentTo(INVITEE_EMAIL);
        List<Mail> toOwner = mailbox.getMailsSentTo(OWNER_EMAIL);
        assertEquals(1, toInvitee.size(), "disconnected -> invitee fallback mail");
        assertEquals(1, toOwner.size(), "owner always (enabled)");
        assertEquals(2, mailbox.getTotalMessagesSent());

        Mail m = toInvitee.getFirst();
        assertTrue(m.getHtml().contains("Discovery Call"), "meeting type name");
        // location present (Meet link, GOOGLE_MEET type)
        assertTrue(m.getHtml().contains("https://meet.google.com/abc-defg-hij"), "location/meet link");
        // manage link from manageToken
        assertTrue(m.getHtml().contains("/booking/"), "manage link path present");
        assertTrue(m.getHtml().contains("/manage"), "manage link suffix present");
        // answers
        assertTrue(m.getHtml().contains("What do you want to discuss?"), "field label");
        assertTrue(m.getHtml().contains("Pricing tiers"), "answer value");
        assertTrue(m.getHtml().contains("Company"));
        assertTrue(m.getHtml().contains("Acme"));
        assertTrue(m.getSubject().toLowerCase().contains("confirmed"));

        // .ics attachment present on an app-sent mail
        assertHasIcsAttachment(m);
    }

    // ---- Confirmed + Google connected: invitee gets NO app mail; owner still does ----

    @Test
    void confirmedWhenGoogleConnectedSuppressesInviteeButOwnerStillGetsMail() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = "https://meet.google.com/xyz";
                },
                true,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertTrue(
                mailbox.getMailsSentTo(INVITEE_EMAIL).isEmpty(),
                "connected -> Google emails the invitee, app must not");
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size(), "owner still gets the app mail");
        assertEquals(1, mailbox.getTotalMessagesSent());
        assertHasIcsAttachment(mailbox.getMailsSentTo(OWNER_EMAIL).getFirst());
    }

    // ---- BookingRequested (PENDING): always to invitee + owner, regardless of Google ----

    @Test
    void requestedAlwaysSendsToInviteeAndOwnerEvenWhenGoogleConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true); // connected, but no Google event exists yet
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.PENDING;
                    b.meetLink = null;
                    b.answers = Map.of("description", "Need a demo");
                },
                true,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleRequested(new BookingRequested(bookingId));

        assertEquals(
                1,
                mailbox.getMailsSentTo(INVITEE_EMAIL).size(),
                "requested is an always-send exception (no Google event)");
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size());
        assertEquals(2, mailbox.getTotalMessagesSent());
        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(m.getSubject().toLowerCase().contains("request"));
        assertTrue(m.getHtml().contains("Need a demo"));
        assertHasIcsAttachment(m);
    }

    // ---- BookingDeclined: always to invitee, regardless of Google ----

    @Test
    void declinedAlwaysSendsToInviteeEvenWhenGoogleConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.DECLINED;
                    b.meetLink = null;
                },
                true,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleDeclined(new BookingDeclined(bookingId));

        assertEquals(
                1,
                mailbox.getMailsSentTo(INVITEE_EMAIL).size(),
                "declined is an always-send exception (no Google event)");
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size());
        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(m.getSubject().toLowerCase().contains("declin"));
    }

    // ---- ownerNotificationsEnabled = false: owner gets nothing; invitee per rules ----

    @Test
    void ownerOptedOutGetsNothingInviteeStillFallbackWhenDisconnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = "https://meet.google.com/opt-out";
                },
                false /* ownerNotificationsEnabled = false */,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertTrue(mailbox.getMailsSentTo(OWNER_EMAIL).isEmpty(), "owner opted out -> no owner mail");
        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size(), "invitee still gets fallback (disconnected)");
        assertEquals(1, mailbox.getTotalMessagesSent());
    }

    @Test
    void ownerOptedOutAndGoogleConnectedSendsNothing() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = "https://meet.google.com/none";
                },
                false,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        assertEquals(
                0, mailbox.getTotalMessagesSent(), "connected (invitee suppressed) + owner opted out -> zero mail");
    }

    // ---- Non-Meet location (PHONE) renders locationDetail, not a link ----

    @Test
    void confirmedPhoneLocationRendersLocationDetail() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = null;
                },
                true,
                LocationType.PHONE,
                "+1 555 0100");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(m.getHtml().contains("+1 555 0100"), "phone locationDetail rendered");
        assertFalse(m.getHtml().contains("meet.google.com"), "no meet link for PHONE type");
    }

    // ---- Reschedule follows the fallback rule too ----

    @Test
    void rescheduleWhenConnectedSuppressesInviteeOwnerStillGets() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        var newStart = Instant.parse("2026-06-10T09:00:00Z");
        long bookingId = seedAt(
                newStart,
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = "https://meet.google.com/resch";
                },
                true,
                LocationType.GOOGLE_MEET,
                null);
        var oldStart = Instant.parse("2026-06-08T09:00:00Z");

        emailService.handleRescheduled(new BookingRescheduled(bookingId, oldStart));

        assertTrue(mailbox.getMailsSentTo(INVITEE_EMAIL).isEmpty());
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size());
        assertTrue(mailbox.getMailsSentTo(OWNER_EMAIL)
                .getFirst()
                .getSubject()
                .toLowerCase()
                .contains("reschedul"));
    }

    // ---- Cancellation: fallback rule, no location/meet link in body ----

    @Test
    void cancellationWhenDisconnectedSendsToBothWithoutMeetLink() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CANCELLED;
                    b.meetLink = "https://meet.google.com/will-not-appear";
                },
                true,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleCancelled(new BookingCancelled(bookingId));

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size());
        Mail m = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertTrue(m.getSubject().toLowerCase().contains("cancel"));
        assertFalse(m.getHtml().contains("will-not-appear"), "cancellation body must not include a meet link");
    }

    // ---- Reminder follows the fallback rule ----

    @Test
    void reminderWhenDisconnectedSendsToBoth() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(
                b -> {
                    b.status = BookingStatus.CONFIRMED;
                    b.meetLink = "https://meet.google.com/rem";
                },
                true,
                LocationType.GOOGLE_MEET,
                null);

        emailService.handleReminder(new ReminderDue(bookingId));

        assertEquals(1, mailbox.getMailsSentTo(INVITEE_EMAIL).size());
        assertEquals(1, mailbox.getMailsSentTo(OWNER_EMAIL).size());
        assertTrue(mailbox.getMailsSentTo(INVITEE_EMAIL)
                .getFirst()
                .getSubject()
                .toLowerCase()
                .contains("reminder"));
    }

    // ---- From header carries owner display name for booking mail ----

    @Test
    void bookingMailFromCarriesOwnerDisplayName() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertEquals("Owner via calit <calit@example.com>", owner.getFrom());
    }

    @Test
    void confirmedOwnerMailContainsManageLink() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        long bookingId = seed(b -> b.status = BookingStatus.CONFIRMED, true, LocationType.PHONE, "+1");

        emailService.handleConfirmed(new BookingConfirmed(bookingId));

        Mail owner = mailbox.getMailsSentTo(OWNER_EMAIL).getFirst();
        assertTrue(
                owner.getHtml().contains("/me/bookings/" + bookingId + "/manage"),
                "owner copy links to the /me manage page");
        Mail invitee = mailbox.getMailsSentTo(INVITEE_EMAIL).getFirst();
        assertFalse(invitee.getHtml().contains("/me/bookings/"), "invitee copy must NOT contain the owner /me link");
    }

    @Test
    void passwordResetMailHasNoPerMessageFrom() {
        mailbox.clear();
        emailService.sendPasswordReset(
                "u@example.com", "https://x/reset", Instant.now().plusSeconds(3600), java.util.Locale.ENGLISH);
        assertNull(
                mailbox.getMailsSentTo("u@example.com").getFirst().getFrom(),
                "no per-message From -> falls back to config default");
    }

    // --- attachment assertion: every app-sent mail carries an .ics ---

    private static void assertHasIcsAttachment(Mail m) {
        assertFalse(m.getAttachments().isEmpty(), "mail must carry an attachment");
        assertTrue(
                m.getAttachments().stream()
                        .anyMatch(a -> "invite.ics".equals(a.getName())
                                || (a.getContentType() != null
                                        && a.getContentType().contains("text/calendar"))),
                "an .ics (text/calendar) attachment must be present");
    }

    // --- seeding helpers ---

    private long seed(
            java.util.function.Consumer<Booking> tweak,
            boolean ownerNotificationsEnabled,
            LocationType locationType,
            String locationDetail) {
        return seedAt(
                Instant.parse("2026-06-08T09:00:00Z"), tweak, ownerNotificationsEnabled, locationType, locationDetail);
    }

    private long seedAt(
            Instant startUtc,
            java.util.function.Consumer<Booking> tweak,
            boolean ownerNotificationsEnabled,
            LocationType locationType,
            String locationDetail) {
        return QuarkusTransaction.requiringNew().call(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = "Europe/Amsterdam";
            s.ownerNotificationsEnabled = ownerNotificationsEnabled;
            s.persist();

            MeetingType t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Discovery Call";
            t.slug = "discovery-" + System.nanoTime();
            t.durationMinutes = 30;
            t.locationType = locationType;
            t.locationDetail = locationDetail;
            t.persist();

            // Global custom fields so answers render with labels in order.
            BookingField f1 = new BookingField();
            f1.ownerId = 1L;
            f1.meetingTypeId = null;
            f1.fieldKey = "description";
            f1.label = "What do you want to discuss?";
            f1.type = FieldType.LONG_TEXT;
            f1.required = false;
            f1.position = 0;
            f1.persist();

            BookingField f2 = new BookingField();
            f2.ownerId = 1L;
            f2.meetingTypeId = null;
            f2.fieldKey = "company";
            f2.label = "Company";
            f2.type = FieldType.SHORT_TEXT;
            f2.required = false;
            f2.position = 1;
            f2.persist();

            Booking b = new Booking();
            b.ownerId = 1L;
            b.meetingTypeId = t.id;
            b.inviteeName = "Sam Invitee";
            b.inviteeEmail = INVITEE_EMAIL;
            b.startUtc = startUtc;
            b.endUtc = startUtc.plus(30, ChronoUnit.MINUTES);
            b.googleEventId = "evt-" + System.nanoTime();
            b.meetLink = null;
            b.status = BookingStatus.CONFIRMED;
            b.answers = Map.of();
            b.manageToken = "tok-" + System.nanoTime();
            b.createdAt = Instant.now();
            tweak.accept(b);
            b.persist();
            return b.id;
        });
    }
}
