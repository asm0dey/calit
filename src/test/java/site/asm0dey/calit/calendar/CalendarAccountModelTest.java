package site.asm0dey.calit.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static site.asm0dey.calit.calendar.CalendarProvider.GOOGLE;
import module java.base;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.test.MultiHostFixtures;

@QuarkusTest
class CalendarAccountModelTest {
    @Inject
    EntityManager em;

    @Test
    @TestTransaction
    void googleQueriesIgnoreCaldavAccounts() {
        google();
        caldav();

        assertThat(GoogleCredential.countForOwner(1L)).isEqualTo(1);
        assertThat(GoogleCredential.listForOwner(1L)).hasSize(1);
        assertThat(CaldavAccount.listForOwner(1L)).hasSize(1);
    }

    @Test
    @TestTransaction
    void googleFreeBusyIgnoresCaldavCalendars() {
        var g = google();
        var c = caldav();
        calendar(g.id, "g-cal");
        calendar(c.id, "c-cal");

        assertThat(SelectedCalendar.readForBusyByAccount(1L, GOOGLE).keySet()).containsExactly(g.id);
        assertThat(SelectedCalendar.readForBusy(1L, CalendarProvider.CALDAV))
            .extracting(s -> s.remoteId)
            .containsExactly("c-cal");
    }

    @Test
    @TestTransaction
    void entityWriteReachesOldColumns() {
        var g = google();
        var b = booking(g.id);

        var row = (Object[]) em
            .createNativeQuery(
                    "SELECT google_event_id, google_calendar_id, google_credential_id FROM booking WHERE id = :id"
            )
            .setParameter("id", b.id)
            .getSingleResult();
        assertThat(row).containsExactly("e1", "cal", g.id);

        MeetingType t = MultiHostFixtures.meetingType(1L, "override-compat", 30);
        t.overrideAccountId = g.id;
        t.overrideRemoteCalendarId = "ocal";
        em.flush();
        var typeRow = (Object[]) em
            .createNativeQuery("SELECT google_calendar_id, google_credential_id FROM meeting_type WHERE id = :id")
            .setParameter("id", t.id)
            .getSingleResult();
        assertThat(typeRow).containsExactly("ocal", g.id);
    }

    @Test
    @TestTransaction
    void oldColumnWriteVisibleToEntity() {
        var b = booking(google().id);
        em
            .createNativeQuery("UPDATE booking SET google_event_id = 'old' WHERE id = :id")
            .setParameter("id", b.id)
            .executeUpdate();
        em.clear();

        assertThat(Booking.<Booking>findById(b.id).eventId).isEqualTo("old");
    }

    @Test
    @TestTransaction
    void caldavPasswordEncryptedAtRest() {
        var c = caldav();
        var stored = (String) em
            .createNativeQuery("SELECT caldav_password FROM calendar_account WHERE id = :id")
            .setParameter("id", c.id)
            .getSingleResult();

        assertThat(stored).startsWith("enc:v1:");
    }

    private Booking booking(Long accountId) {
        Booking b = new Booking();
        b.ownerId = 1L;
        b.meetingTypeId = MultiHostFixtures.meetingType(1L, "compat-" + accountId, 30).id;
        b.inviteeName = "Sam";
        b.inviteeEmail = "sam@example.com";
        b.startUtc = Instant.parse("2026-11-02T10:00:00Z");
        b.endUtc = b.startUtc.plusSeconds(1800);
        b.status = BookingStatus.CONFIRMED;
        b.createdAt = Instant.now();
        b.manageToken = UUID.randomUUID().toString();
        b.eventId = "e1";
        b.calendarRemoteId = "cal";
        b.calendarAccountId = accountId;
        b.persist();
        em.flush();
        return b;
    }

    private static GoogleCredential google() {
        GoogleCredential g = new GoogleCredential();
        g.ownerId = 1L;
        g.refreshToken = "r";
        g.googleSub = "sub-model";
        g.persist();
        return g;
    }

    private static CaldavAccount caldav() {
        CaldavAccount c = new CaldavAccount();
        c.ownerId = 1L;
        c.serverUrl = "https://dav.example.com/";
        c.username = "sam";
        c.password = "secret";
        c.persist();
        return c;
    }

    private static void calendar(Long accountId, String remoteId) {
        SelectedCalendar s = new SelectedCalendar();
        s.ownerId = 1L;
        s.accountId = accountId;
        s.remoteId = remoteId;
        s.summary = remoteId;
        s.readForBusy = true;
        s.persist();
    }
}
