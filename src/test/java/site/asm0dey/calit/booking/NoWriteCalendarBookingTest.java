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
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarProvider;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.GoogleCalendarPort;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * A connected owner who has selected no write calendar still takes bookings: no Mirror event is written, and calit
 * sends its own .ics to everyone, the Creator included, exactly as in degraded mode.
 */
@QuarkusTest
class NoWriteCalendarBookingTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant T10 = DAY.atTime(10, 0).atZone(ZONE).toInstant();
    private static final String OWNER_EMAIL = "owner@example.com";
    private static final String INVITEE_EMAIL = "sam@example.com";
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
        when(google.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(caldav.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Test
    void googleOwnerWithoutWriteCalendarBooksAndEveryoneGetsIcs() {
        when(google.isConnected(anyLong())).thenReturn(true);
        String slug = seed(() -> {
            var g = new GoogleCredential();
            g.ownerId = 1L;
            g.refreshToken = "rt";
            g.googleSub = "sub-no-write";
            g.persist();
        });

        Booking b = book(slug);

        assertThat(b.status).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(b.eventId).isNull();
        assertThat(b.calendarRef()).isNull();
        verify(google, never()).createEvent(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        );
        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:REQUEST");
        assertThat(ics(OWNER_EMAIL)).contains("METHOD:REQUEST");
    }

    @Test
    void caldavOwnerWithoutWriteCalendarBooksAndEveryoneGetsIcs() {
        when(caldav.isConnected(anyLong())).thenReturn(true);
        String slug = seed(NoWriteCalendarBookingTest::caldavAccount);

        Booking b = book(slug);

        assertThat(b.eventId).isNull();
        verify(caldav, never()).createEvent(
                anyLong(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyBoolean(),
                any()
        );
        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:REQUEST");
        assertThat(ics(OWNER_EMAIL)).contains("METHOD:REQUEST");
    }

    @Test
    void cancelOfBookingWithoutMirrorEventSendsCancelIcsToEveryone() {
        when(caldav.isConnected(anyLong())).thenReturn(true);
        String slug = seed(NoWriteCalendarBookingTest::caldavAccount);
        Booking b = book(slug);
        mailbox.clear();

        bookingService.cancel(b.manageToken, false);

        verify(caldav, never()).deleteEvent(anyLong(), any(), any());
        verify(google, never()).deleteEvent(anyLong(), any(), any());
        assertThat(ics(INVITEE_EMAIL)).contains("METHOD:CANCEL");
        assertThat(ics(OWNER_EMAIL)).contains("METHOD:CANCEL");
    }

    private Booking book(String slug) {
        return bookingService.book(1L, slug, T10, "Sam", INVITEE_EMAIL, Map.of(), "tok", "", "en", List.of());
    }

    private static void caldavAccount() {
        var a = new CaldavAccount();
        a.ownerId = 1L;
        a.serverUrl = "https://dav.example.com/";
        a.username = "alice";
        a.password = "secret";
        a.principalUrl = "https://dav.example.com/p/alice/";
        a.homeUrl = "https://dav.example.com/cal/";
        a.persist();
    }

    /**
     * Owner 1 with notifications on, an account from {@code account} and no selected calendar; returns a slug.
     */
    private static String seed(Runnable account) {
        var slug = "no-write-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            account.run();
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
            t.name = "No write calendar";
            t.slug = slug;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.locationType = MeetingType.LocationType.GOOGLE_MEET;
            t.persist();
            var r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = DAY.getDayOfWeek();
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(12, 0);
            r.persist();
        });
        return slug;
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
