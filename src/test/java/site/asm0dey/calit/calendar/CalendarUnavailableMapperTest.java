package site.asm0dey.calit.calendar;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * A booking POST over an unreadable calendar reaches {@link CalendarUnavailableMapper}: 503 + unavailable page.
 */
@QuarkusTest
class CalendarUnavailableMapperTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");
    private static final LocalDate DAY = Instant.now().atZone(ZONE).toLocalDate().plusDays(7);
    @InjectMock
    CalendarPort calendarPort;

    @Test
    void bookingPostOverUnreadableCalendarReturns503UnavailablePage() {
        var slug = "unavail-post-" + System.nanoTime();
        // Admin user is always id 1 with username "admin" (test invariant).
        QuarkusTransaction.requiringNew().run(() -> {
            if (OwnerSettings.forOwner(1L) == null) {
                var s = new OwnerSettings();
                s.ownerId = 1L;
                s.ownerName = "Owner";
                s.ownerEmail = "owner@example.com";
                s.timezone = ZONE.getId();
                s.persist();
            }
            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = slug;
            t.slug = slug;
            t.durationMinutes = 60;
            t.minNoticeMinutes = 0;
            t.horizonDays = 30;
            t.locationType = LocationType.GOOGLE_MEET;
            t.requiresApproval = false;
            t.persist();
            var r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = DAY.getDayOfWeek();
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(11, 0);
            r.meetingTypeId = t.id;
            r.persist();
        });
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenThrow(new CalendarUnavailableException("down"));

        given()
            .contentType("application/json")
            .body(
                    "{\"user\":\"admin\",\"slug\":\""
                    + slug
                    + "\",\"startUtc\":\""
                    + DAY.atTime(9, 0).atZone(ZONE).toInstant()
                    + "\",\"inviteeName\":\"Sam\",\"inviteeEmail\":\"sam@example.com\","
                    + "\"answers\":{},\"turnstileToken\":\"tok\",\"honeypot\":\"\"}"
            )
            .when()
            .post("/api/bookings")
            .then()
            .statusCode(503)
            .contentType(containsString("text/html"))
            .body(containsString("Scheduling temporarily unavailable"));
    }
}
