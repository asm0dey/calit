package site.asm0dey.calit.booking;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.caldav.CaldavCalendarPort;
import site.asm0dey.calit.calendar.*;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.GoogleCalendarPort;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * Whose invites a booking's mail carries follows the Mirror event the booking actually had, over its whole life: a
 * booking that never had one keeps calit's invites after a write calendar is selected, and a decline after a
 * re-approval reschedule (which removed the event) still knows which provider had invited the attendees.
 */
@QuarkusTest
class MirrorEventMailLifecycleTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant T10 = DAY.atTime(10, 0).atZone(ZONE).toInstant();
    private static final Instant T11 = DAY.atTime(11, 0).atZone(ZONE).toInstant();
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "sam@example.com";
    private static final String GUEST_EMAIL = "guest@example.com";
    @Inject
    BookingService bookingService;
    @Inject
    MockMailbox mailbox;
    @InjectMock
    GoogleCalendarPort google;
    @InjectMock
    CaldavCalendarPort caldav;

    @BeforeEach
    void init() {
        mailbox.clear();
        when(google.provider()).thenReturn(CalendarProvider.GOOGLE);
        when(caldav.provider()).thenReturn(CalendarProvider.CALDAV);
        when(google.isConnected(anyLong())).thenReturn(true);
        when(caldav.isConnected(anyLong())).thenReturn(true);
        when(google.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(caldav.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void selectingAGoogleWriteTargetLaterKeepsCalitInvites() {
        Long account = tx(MirrorEventMailLifecycleTest::googleAccount);
        Booking b = book(seedType(false, null));
        tx(() -> calendar(account, "owner@example.com", true));

        rescheduleAndCancelSendCalitIcsToInviteeAndCreator(b);
    }

    @Test
    void selectingACaldavWriteTargetLaterKeepsCalitInvites() {
        Long account = tx(MirrorEventMailLifecycleTest::caldavAccount);
        Booking b = book(seedType(false, null));
        tx(() -> calendar(account, "/cal/work/", true));

        rescheduleAndCancelSendCalitIcsToInviteeAndCreator(b);
    }

    private void rescheduleAndCancelSendCalitIcsToInviteeAndCreator(Booking b) {
        assertThat(b.eventId).isNull();
        mailbox.clear();
        bookingService.reschedule(b.manageToken, T11);

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:REQUEST");
        assertThat(ics(OWNER_EMAIL)).contains("METHOD:REQUEST");

        mailbox.clear();
        bookingService.cancel(b.manageToken, false);

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL");
        assertThat(ics(OWNER_EMAIL)).contains("METHOD:CANCEL");
        verify(google, never()).updateEvent(anyLong(), any(), any(), any(), any(), any());
        verify(caldav, never()).updateEvent(anyLong(), any(), any(), any(), any(), any());
        verify(google, never()).deleteEvent(anyLong(), any(), any());
        verify(caldav, never()).deleteEvent(anyLong(), any(), any());
    }

    @Test
    void declineAfterReapprovalOfACaldavEventSendsCalitCancel() {
        Long googleAccount = tx(MirrorEventMailLifecycleTest::googleAccount);
        Long davAccount = tx(MirrorEventMailLifecycleTest::caldavAccount);
        tx(() -> calendar(googleAccount, "owner@example.com", true));
        var dav = new CalendarRef(davAccount, "/cal/work/");
        tx(() -> calendar(davAccount, dav.remoteCalendarId(), false));
        when(caldav.createEvent(anyLong(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("/cal/work/evt.ics", null, null, dav));

        declineAfterReapproval(seedType(true, dav));

        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL");
        assertThat(ics(GUEST_EMAIL)).contains("METHOD:CANCEL");
    }

    @Test
    void declineAfterReapprovalOfAGoogleEventSendsNoCalitIcs() {
        Long googleAccount = tx(MirrorEventMailLifecycleTest::googleAccount);
        Long davAccount = tx(MirrorEventMailLifecycleTest::caldavAccount);
        tx(() -> calendar(davAccount, "/cal/work/", true));
        var g = new CalendarRef(googleAccount, "owner@example.com");
        tx(() -> calendar(googleAccount, g.remoteCalendarId(), false));
        when(google.createEvent(anyLong(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-g", null, null, g));

        declineAfterReapproval(seedType(true, g));

        assertNoIcs(INVITEE_EMAIL);
        assertNoIcs(GUEST_EMAIL);
    }

    /**
     * Book, approve (Mirror event created), invitee reschedules (back to PENDING, event removed), owner declines.
     */
    private void declineAfterReapproval(String slug) {
        Booking b =
                bookingService.book(
                        1L,
                        slug,
                        T10,
                        "Sam",
                        INVITEE_EMAIL,
                        Map.of(),
                        "tok",
                        "",
                        "en",
                        List.of(GUEST_EMAIL)
        );
        bookingService.approve(b.id);
        assertThat(tx(() -> Booking.<Booking>findById(b.id).eventId)).isNotNull();
        bookingService.reschedule(b.manageToken, T11);
        assertThat(tx(() -> Booking.<Booking>findById(b.id).status)).isEqualTo(BookingStatus.PENDING);
        mailbox.clear();

        bookingService.decline(b.id);
    }

    private Booking book(String slug) {
        return bookingService.book(1L, slug, T10, "Sam", INVITEE_EMAIL, Map.of(), "tok", "", "en", List.of());
    }

    private static <T> T tx(Callable<T> work) {
        return QuarkusTransaction.requiringNew().call(work);
    }

    private static Long googleAccount() {
        var g = new GoogleCredential();
        g.ownerId = 1L;
        g.refreshToken = "rt";
        g.googleSub = "sub-lifecycle";
        g.persist();
        return g.id;
    }

    private static Long caldavAccount() {
        var a = new CaldavAccount();
        a.ownerId = 1L;
        a.serverUrl = "https://dav.example.com/";
        a.username = "alice";
        a.password = "secret";
        a.principalUrl = "https://dav.example.com/p/alice/";
        a.homeUrl = "https://dav.example.com/cal/";
        a.persist();
        return a.id;
    }

    private static Long calendar(Long accountId, String remoteId, boolean writeTarget) {
        var c = new SelectedCalendar();
        c.ownerId = 1L;
        c.accountId = accountId;
        c.remoteId = remoteId;
        c.summary = remoteId;
        c.writeTarget = writeTarget;
        c.persist();
        return c.id;
    }

    /**
     * Owner 1 (notifications on) and a 30-minute type writing on {@code override} when given; returns its slug.
     */
    private static String seedType(boolean requiresApproval, CalendarRef override) {
        var slug = "lifecycle-" + UUID.randomUUID();
        tx(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) {
                s = new OwnerSettings();
                s.ownerId = 1L;
            }
            s.ownerName = "Owner";
            s.ownerEmail = OWNER_EMAIL;
            s.timezone = ZONE.getId();
            s.ownerNotificationsEnabled = true;
            s.persist();
            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Lifecycle";
            t.slug = slug;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.locationType = MeetingType.LocationType.PHONE;
            t.requiresApproval = requiresApproval;
            if (override != null) {
                t.overrideAccountId = override.accountId();
                t.overrideRemoteCalendarId = override.remoteCalendarId();
            }
            t.persist();
            if (AvailabilityRule.count("ownerId", 1L) == 0) {
                var r = new AvailabilityRule();
                r.ownerId = 1L;
                r.dayOfWeek = DAY.getDayOfWeek();
                r.startTime = LocalTime.of(9, 0);
                r.endTime = LocalTime.of(12, 0);
                r.persist();
            }
            return null;
        });
        return slug;
    }

    private void assertNoIcs(String to) {
        assertThat(mailbox.getMailsSentTo(to))
            .singleElement()
            .satisfies(m -> assertThat(m.getAttachments()).isEmpty());
    }

    private String ics(String to) {
        var mails = mailbox.getMailsSentTo(to);
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
