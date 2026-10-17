package site.asm0dey.calit.email;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.booking.events.BookingConfirmed;
import site.asm0dey.calit.caldav.CaldavCalendarPort;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.GoogleCalendarPort;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.test.MultiHostFixtures;

/**
 * Who gets calit's .ics when the Mirror event lives on CalDAV (spec call 12): the invitee, guests and co-hosts do;
 * the Creator, whose calendar already holds the event, does not. Both providers count as connected; which one holds
 * the Mirror event comes from the account rows. Cancels go through {@link BookingService#cancel}, which clears the
 * booking's calendar address before the mail goes out.
 */
@QuarkusTest
class CaldavInviteMatrixTest {
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "invitee@example.com";
    private static final String GUEST_EMAIL = "guest@example.com";
    @Inject
    EmailService emailService;
    @Inject
    MockMailbox mailbox;
    @Inject
    BookingService bookingService;
    // the remote half of a cancel is stubbed; the mail decision under test reads only account rows
    @InjectMock
    GoogleCalendarPort google;
    @InjectMock
    CaldavCalendarPort caldav;

    @BeforeEach
    void clear() {
        mailbox.clear();
        when(google.provider()).thenReturn(CalendarProvider.GOOGLE);
        when(caldav.provider()).thenReturn(CalendarProvider.CALDAV);
        when(google.isConnected(anyLong())).thenReturn(true);
        when(caldav.isConnected(anyLong())).thenReturn(true);
    }

    @Test
    void inviteeAndGuestGetIcs() {
        long id = seedSingle(caldavWriteTarget(), BookingStatus.CONFIRMED);

        emailService.handleConfirmed(new BookingConfirmed(id));

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:REQUEST");
        assertThat(ics(GUEST_EMAIL)).contains("METHOD:REQUEST");
    }

    @Test
    void creatorGetsNoIcs() {
        long id = seedSingle(caldavWriteTarget(), BookingStatus.CONFIRMED);

        emailService.handleConfirmed(new BookingConfirmed(id));

        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL))
            .singleElement()
            .satisfies(m -> assertThat(m.getAttachments()).isEmpty());
    }

    @Test
    void cancelOfCaldavEventSendsCancelIcsEvenWithGoogleWriteTarget() {
        googleCalendar(true);
        Booking b = booking(seedSingle(caldavCalendar(false), BookingStatus.CONFIRMED));

        bookingService.cancel(b.manageToken, false);

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL");
        assertThat(ics(GUEST_EMAIL)).contains("METHOD:CANCEL");
        assertThat(mailbox.getMailsSentTo(OWNER_EMAIL)).allSatisfy(m -> assertThat(m.getAttachments()).isEmpty());
    }

    @Test
    void cancelOfGoogleEventSendsNoIcsEvenWithCaldavWriteTarget() {
        caldavCalendar(true);
        Booking b = booking(seedSingle(googleCalendar(false), BookingStatus.CONFIRMED));

        bookingService.cancel(b.manageToken, false);

        assertNoIcs(INVITEE_EMAIL);
        assertNoIcs(GUEST_EMAIL);
    }

    @Test
    void groupCancelOfCaldavEventSendsCancelIcsEvenWithGoogleWriteTarget() {
        googleCalendar(true);
        long leadId = seedGroup(caldavCalendar(false));

        bookingService.cancel(booking(leadId).manageToken, false);

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL");
        assertThat(ics("Cohost@x.com")).contains("METHOD:CANCEL");
        assertNoIcs("Creator@x.com");
    }

    @Test
    void groupCancelOfGoogleEventSendsNoIcsEvenWithCaldavWriteTarget() {
        caldavCalendar(true);
        long leadId = seedGroup(googleCalendar(false));

        bookingService.cancel(booking(leadId).manageToken, false);

        assertNoIcs(INVITEE_EMAIL);
        assertNoIcs("Cohost@x.com");
    }

    @Test
    void googleOwnerUnchanged() {
        long id = seedSingle(googleWriteTarget(), BookingStatus.CONFIRMED);

        emailService.handleConfirmed(new BookingConfirmed(id));

        assertThat(mailbox.getMailsSentTo(INVITEE_EMAIL))
            .singleElement()
            .satisfies(m -> assertThat(m.getAttachments()).isEmpty());
        assertThat(mailbox.getMailsSentTo(GUEST_EMAIL))
            .singleElement()
            .satisfies(m -> assertThat(m.getAttachments()).isEmpty());
    }

    @Test
    void groupCohostGetsIcsCreatorDoesNot() {
        long leadId = seedGroup(caldavWriteTarget());

        emailService.handleConfirmed(new BookingConfirmed(leadId));

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:REQUEST");
        assertThat(ics("Cohost@x.com")).contains("METHOD:REQUEST");
        assertNoIcs("Creator@x.com");
    }

    /**
     * A two-host group whose lead row's Mirror event is on {@code mirror}; returns the lead id.
     */
    private static long seedGroup(SelectedCalendar mirror) {
        return QuarkusTransaction.requiringNew().call(() -> {
            MultiHostFixtures.enabledUser("cohost");
            MultiHostFixtures.settings(1L, "Creator");
            MultiHostFixtures.settings(2L, "Cohost");
            MeetingType type = MultiHostFixtures.acceptedTwoHostType(1L, 2L, "pair-" + System.nanoTime(), 30, false);
            var groupId = UUID.randomUUID();
            Booking lead = booking(1L, type, BookingStatus.CONFIRMED);
            lead.groupId = groupId;
            lead.eventId = "evt-group";
            lead.calendarAccountId = mirror.accountId;
            lead.calendarRemoteId = mirror.remoteId;
            lead.persist();
            Booking cohostRow = booking(2L, type, BookingStatus.CONFIRMED);
            cohostRow.groupId = groupId;
            cohostRow.persist();
            return lead.id;
        });
    }

    private static Booking booking(long id) {
        return QuarkusTransaction
            .requiringNew()
            .call(() -> Booking.findById(id));
    }

    private void assertNoIcs(String to) {
        assertThat(mailbox.getMailsSentTo(to))
            .singleElement()
            .satisfies(m -> assertThat(m.getAttachments()).isEmpty());
    }

    private static SelectedCalendar caldavWriteTarget() {
        return caldavCalendar(true);
    }

    private static SelectedCalendar googleWriteTarget() {
        return googleCalendar(true);
    }

    private static SelectedCalendar caldavCalendar(boolean writeTarget) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var a = new CaldavAccount();
            a.ownerId = 1L;
            a.serverUrl = "https://dav.example.com/";
            a.username = "alice";
            a.password = "secret";
            a.principalUrl = "https://dav.example.com/p/alice/";
            a.homeUrl = "https://dav.example.com/cal/";
            a.persist();
            return calendar(a.id, "/cal/work/", writeTarget);
        });
    }

    private static SelectedCalendar googleCalendar(boolean writeTarget) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var g = new GoogleCredential();
            g.ownerId = 1L;
            g.refreshToken = "rt";
            g.googleSub = "sub-matrix";
            g.persist();
            return calendar(g.id, "owner@example.com", writeTarget);
        });
    }

    private static SelectedCalendar calendar(Long accountId, String remoteId, boolean writeTarget) {
        var c = new SelectedCalendar();
        c.ownerId = 1L;
        c.accountId = accountId;
        c.remoteId = remoteId;
        c.summary = remoteId;
        c.writeTarget = writeTarget;
        c.persist();
        return c;
    }

    /**
     * Owner 1's single booking with one guest, its Mirror event on {@code mirror}; returns the booking id.
     */
    private static long seedSingle(SelectedCalendar mirror, BookingStatus status) {
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
            MeetingType t = MultiHostFixtures.meetingType(1L, "matrix-" + System.nanoTime(), 30);
            Booking b = booking(1L, t, status);
            b.eventId = "/cal/work/evt.ics";
            b.calendarAccountId = mirror.accountId;
            b.calendarRemoteId = mirror.remoteId;
            b.persist();
            BookingGuest g = new BookingGuest();
            g.ownerId = 1L;
            g.bookingId = b.id;
            g.email = GUEST_EMAIL;
            g.status = GuestStatus.INVITED;
            g.declineToken = "dt-" + System.nanoTime();
            g.createdAt = Instant.now();
            g.persist();
            return b.id;
        });
    }

    private static Booking booking(Long ownerId, MeetingType type, BookingStatus status) {
        Booking b = new Booking();
        b.ownerId = ownerId;
        b.meetingTypeId = type.id;
        b.inviteeName = "Sam Invitee";
        b.inviteeEmail = INVITEE_EMAIL;
        b.startUtc = Instant.parse("2026-06-08T09:00:00Z");
        b.endUtc = b.startUtc.plus(30, ChronoUnit.MINUTES);
        b.status = status;
        b.manageToken = "tok-" + ownerId + "-" + System.nanoTime();
        b.createdAt = Instant.now();
        return b;
    }

    private String ics(String to) {
        List<Mail> mails = mailbox.getMailsSentTo(to);
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().getAttachments()).as(".ics attached for " + to).hasSize(1);
        var data = Multi
            .createFrom()
            .publisher(mails.getFirst().getAttachments().getFirst().getData())
            .collect()
            .asList()
            .await()
            .indefinitely();
        var bytes = new byte[data.size()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = data.get(i);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
