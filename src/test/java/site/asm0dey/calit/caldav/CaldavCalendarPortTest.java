package site.asm0dey.calit.caldav;

import module java.base;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.calendar.BusyInterval;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarPort;
import site.asm0dey.calit.calendar.CalendarRef;
import site.asm0dey.calit.calendar.CalendarUnavailableException;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.email.EmailService;
import site.asm0dey.calit.google.GoogleCalendarPort;

@QuarkusTest
@QuarkusTestResource(value = RadicaleResource.class, restrictToAnnotatedClass = true)
class CaldavCalendarPortTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    private static final Instant T10 = DAY.atTime(10, 0).atZone(ZONE).toInstant();
    private static final Instant T1030 = DAY.atTime(10, 30).atZone(ZONE).toInstant();
    private static final Instant T11 = DAY.atTime(11, 0).atZone(ZONE).toInstant();
    private static final Instant T1130 = DAY.atTime(11, 30).atZone(ZONE).toInstant();
    private static final Instant DAY_START = DAY.atStartOfDay(ZONE).toInstant();
    private static final Instant DAY_END = DAY.plusDays(1).atStartOfDay(ZONE).toInstant();
    @Inject
    CaldavCalendarPort port;
    @Inject
    CaldavClient client;
    @Inject
    CaldavHttp http;
    @Inject
    BookingService bookingService;
    @Inject
    EmailService emailService;
    // default: test classes are validated on every boot, including ones without RadicaleResource
    @ConfigProperty(name = "caldav.test.url", defaultValue = "unset")
    String baseUrl;
    Long accountId;
    String calendarHref;

    @BeforeEach
    void connectOwner1() {
        var d = client.discover(baseUrl, "alice", "secret");
        var a = new CaldavAccount();
        a.ownerId = 1L;
        a.serverUrl = baseUrl;
        a.username = "alice";
        a.password = "secret";
        a.principalUrl = d.principalUrl().toString();
        a.homeUrl = d.homeUrl().toString();
        calendarHref = mkcalendar(a, "port-" + UUID.randomUUID().toString().substring(0, 8)).toString();
        accountId = QuarkusTransaction.requiringNew().call(() -> {
            var s = new OwnerSettings();
            s.ownerId = 1L;
            s.ownerName = "Owner";
            s.ownerEmail = "owner@example.com";
            s.timezone = ZONE.getId();
            s.persist();
            a.persist();
            var c = new SelectedCalendar();
            c.ownerId = 1L;
            c.accountId = a.id;
            c.remoteId = calendarHref;
            c.summary = "Port";
            c.readForBusy = true;
            c.writeTarget = true;
            c.persist();
            return a.id;
        });
    }

    URI mkcalendar(CaldavAccount a, String name) {
        var url = URI.create(a.homeUrl).resolve(name + "/");
        var body =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <C:mkcalendar xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <D:set><D:prop>
                    <C:supported-calendar-component-set><C:comp name="VEVENT"/></C:supported-calendar-component-set>
                  </D:prop></D:set>
                </C:mkcalendar>
                """;
        var res = http.send(a, "MKCALENDAR", url, Map.of("Content-Type", "application/xml; charset=utf-8"), body);
        assertThat(res.status()).isEqualTo(201);
        return url;
    }

    String create(String uid, Instant start, Instant end) {
        return port
            .createEvent(
                    1L,
                    null,
                    uid,
                    "Call with Sam",
                    "Agenda",
                    start,
                    end,
                    List.of("sam@example.com", "owner@example.com", "guest@example.com"),
                    true,
                    "Room 1"
            )
            .eventId();
    }

    String fetch(String eventId) {
        CaldavAccount a = QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.findById(accountId));
        return client.get(a, URI.create(a.homeUrl).resolve(eventId)).ics();
    }

    @Test
    void createThenBusyBlocksSlot() {
        var created =
                port.createEvent(1L, null, UUID.randomUUID().toString(), "S", "", T10, T1030, List.of(), false, null);

        assertThat(created.eventId()).startsWith("/").doesNotContain("://").hasSizeLessThanOrEqualTo(255);
        assertThat(created.calendar()).isEqualTo(new CalendarRef(accountId, calendarHref));
        assertThat(created.meetLink()).isNull();
        assertThat(port.freeBusy(1L, DAY_START, DAY_END)).contains(new BusyInterval(T10, T1030));
    }

    @Test
    void createdEventHasNoAttendeesAndGuestsInDescription() {
        var ics = fetch(create(UUID.randomUUID().toString(), T10, T1030));

        assertThat(ics).doesNotContain("ATTENDEE", "ORGANIZER", "METHOD:").contains("LOCATION:Room 1");
        assertThat(ics.replace("\r\n ", "")).contains("guest@example.com").contains("Agenda");
    }

    @Test
    void rescheduleMovesEvent() {
        var eventId = create(UUID.randomUUID().toString(), T10, T1030);

        port.updateEvent(1L, new CalendarRef(accountId, calendarHref), eventId, T11, T1130, null);

        assertThat(port.freeBusy(1L, DAY_START, DAY_END)).containsExactly(new BusyInterval(T11, T1130));
        assertThat(fetch(eventId)).contains("SEQUENCE:1");
    }

    @Test
    void updateDetailsKeepsTheAttendeeList() {
        var eventId = create(UUID.randomUUID().toString(), T10, T1030);

        port.updateEventDetails(1L, null, eventId, "Renamed", "New agenda", null);

        assertThat(fetch(eventId).replace("\r\n ", ""))
            .contains("SUMMARY:Renamed", "New agenda", "guest@example.com")
            .doesNotContain("Agenda\\n");
    }

    @Test
    void deleteFreesSlot() {
        var eventId = create(UUID.randomUUID().toString(), T10, T1030);

        port.deleteEvent(1L, new CalendarRef(accountId, calendarHref), eventId);
        port.deleteEvent(1L, new CalendarRef(accountId, calendarHref), eventId);

        assertThat(port.freeBusy(1L, DAY_START, DAY_END)).isEmpty();
    }

    @Test
    void createdEventUidMatchesInviteeIcs() {
        // the routing port is Task 6; until then BookingService reaches CalDAV through this stand-in
        QuarkusMock.installMockForType(
                Mockito.mock(GoogleCalendarPort.class, AdditionalAnswers.delegatesTo(port)),
                CalendarPort.class
        );
        var slug = "caldav-" + UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = "CalDAV call";
            t.slug = slug;
            t.durationMinutes = 30;
            t.minNoticeMinutes = 0;
            t.horizonDays = 50_000;
            t.locationType = MeetingType.LocationType.PHONE;
            t.persist();
            var r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = DAY.getDayOfWeek();
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(12, 0);
            r.persist();
        });

        Booking b = bookingService.book(1L, slug, T10, "Sam", "sam@example.com", Map.of(), "tok", "", "en", List.of());

        var inviteeIcs = new String(emailService.inviteeIcs(b.id).orElseThrow(), StandardCharsets.UTF_8);
        assertThat(uid(fetch(b.eventId))).isEqualTo(uid(inviteeIcs)).isEqualTo(b.manageToken);
    }

    @Test
    void wrongPasswordFailsClosedAndFlagsReconnect() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> CaldavAccount.<CaldavAccount>findById(accountId).password = "wrong");

        assertThatThrownBy(() -> port.freeBusy(1L, DAY_START, DAY_END)).isInstanceOf(CalendarUnavailableException.class);
        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.<CaldavAccount>findById(accountId).needsReconnect)
        )
            .isTrue();
        assertThatThrownBy(() -> port.freeBusy(1L, DAY_START, DAY_END))
            .as("a flagged account fails closed without calling the server")
            .isInstanceOf(CalendarUnavailableException.class);
    }

    @Test
    void eventPathOfALongRealisticCalendarFitsTheColumn() {
        var href =
                "https://cloud.example.com/remote.php/dav/calendars/firstname.lastname@example.com/"
                + "team-planning-and-customer-meetings-2026/";
        var uid = UUID.randomUUID().toString();

        assertThat(CaldavCalendarPort.eventUrl(href, uid).getRawPath())
            .startsWith("/remote.php/")
            .endsWith(uid + ".ics")
            .hasSizeLessThanOrEqualTo(255);
        assertThatThrownBy(() -> CaldavCalendarPort.eventUrl("https://h/" + "x".repeat(250) + "/", uid))
            .isInstanceOf(CaldavException.class);
    }

    static String uid(String ics) {
        return ics
            .lines()
            .filter(l -> l.startsWith("UID:"))
            .findFirst()
            .orElseThrow()
            .substring(4)
            .strip();
    }
}
