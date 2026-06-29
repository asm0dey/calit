package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class ManageBookingTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    /** Seeds an AUTO booking and returns its unguessable manageToken (NOT the numeric id). */
    @Transactional
    String seedBooking() {
        // Idempotent across the multiple seedBooking() calls in this class (committed tx).
        Booking.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "manage-type");
        MeetingType.delete("slug", "manage-type");
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Manage Type";
        t.slug = "manage-type";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        // Auto type, no abuse guards configured → empty Turnstile token + blank honeypot.
        Booking b = bookingService.book(
                1L,
                "manage-type",
                slot.start().toInstant(),
                "Manage Me",
                "manage@example.com",
                java.util.Map.of(),
                "",
                "",
                "en",
                List.of());
        return b.manageToken;
    }

    @Test
    void managePageRendersForValidToken() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-m", "https://meet.google.com/manage-link", "h"));
        var token = seedBooking();

        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("Reschedule"))
                .body(containsString("Cancel"))
                // All action URLs are keyed by the token, never a numeric id.
                .body(containsString("/booking/" + token + "/reschedule"))
                .body(containsString("/booking/" + token + "/cancel"))
                // Viewer-local machinery on the manage page: data-utc instants + picker + script + label.
                .body(containsString("data-utc=\""))
                .body(containsString("id=\"tz-picker\""))
                .body(containsString("CALIT_TZ_REFORMAT"))
                .body(containsString("Times shown in:"));
    }

    @Test
    void managePageReturns404ForUnknownToken() {
        given().when()
                .get("/booking/00000000-0000-0000-0000-000000000000/manage")
                .then()
                .statusCode(404);
    }

    @Test
    void rescheduleSubmitsAbsoluteInstantUnaffectedByDisplayZone() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-m3", "https://meet.google.com/manage-link3", "h"));
        var token = seedBooking();

        // Pull a reschedule slot's absolute UTC instant from the manage page's radio value,
        // submit it, and assert the confirmation echoes the SAME instant as a data-utc — the
        // viewer's display zone is purely cosmetic and never changes the booked instant.
        String manage = given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        var startUtc =
                manage.substring(manage.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        startUtc = startUtc.substring(0, startUtc.indexOf('"'));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", startUtc)
                .when()
                .post("/booking/" + token + "/reschedule")
                .then()
                .statusCode(200)
                .body(containsString("data-utc=\"" + startUtc + "\""));
    }

    @Test
    void cancelMarksBookingCancelled() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-m2", "https://meet.google.com/manage-link2", "h"));
        var token = seedBooking();

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/booking/" + token + "/cancel")
                .then()
                .statusCode(200)
                .body(containsString("cancelled"));
    }
}
