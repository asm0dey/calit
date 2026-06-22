package site.asm0dey.calit.web;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CalendarUnavailableException;

import java.time.LocalTime;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@QuarkusTest
class PublicPageUnavailableTest {

    @InjectMock
    CalendarPort calendarPort;

    @Test
    void bookingPageShowsUnavailableWhenCalendarUnreadable() {
        // Admin user is always id 1 with username "admin" (test invariant). Seed its booking surface.
        QuarkusTransaction.requiringNew().run(() -> {
            OwnerSettings s = OwnerSettings.forOwner(1L);
            if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
            s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
            s.persist();
            MeetingType t = new MeetingType();
            t.ownerId = 1L; t.name = "Intro"; t.slug = "intro-unavail"; t.durationMinutes = 60;
            t.bufferBeforeMinutes = 0; t.bufferAfterMinutes = 0;
            t.minNoticeMinutes = 0; t.horizonDays = 30;
            t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = false;
            t.persist();
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L; r.dayOfWeek = java.time.LocalDate.now().getDayOfWeek(); r.meetingTypeId = null;
            r.startTime = LocalTime.parse("00:00"); r.endTime = LocalTime.parse("23:59");
            r.persist();
        });

        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any()))
                .thenThrow(new CalendarUnavailableException("down"));

        given().when().get("/admin/intro-unavail")
                .then().statusCode(200)
                .body(containsString("Scheduling temporarily unavailable"));
    }
}
