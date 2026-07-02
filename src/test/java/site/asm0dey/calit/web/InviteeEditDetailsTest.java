package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
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
class InviteeEditDetailsTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Transactional
    String seed() {
        Booking.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "invitee-edit");
        MeetingType.delete("slug", "invitee-edit");
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
        t.name = "Invitee Edit Type";
        t.slug = "invitee-edit";
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
        return bookingService.book(
                        1L,
                        "invitee-edit",
                        slot.start().toInstant(),
                        "Pat",
                        "pat@example.com",
                        java.util.Map.of(),
                        "",
                        "",
                        "en",
                        List.of())
                .manageToken;
    }

    @Test
    void managePageShowsEditDetailsForm() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ie", "https://meet.google.com/ie", "h"));
        var token = seed();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/booking/" + token + "/edit-details"))
                .body(containsString("name=\"title\""))
                .body(containsString("name=\"description\""));
    }

    @Test
    void inviteeEditsNameDescriptionAndGuestThenSeesHub() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Roadmap sync")
                .formParam("description", "Q3 planning")
                .formParam("guests", "ana@example.com")
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(200)
                .body(containsString("value=\"Roadmap sync\""))
                .body(containsString("/booking/" + token + "/edit-details"));

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findByManageToken(token));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
    }

    @Test
    void cancelConfirmPageShowsEditedName() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();
        bookingService.updateDetails(token, "Roadmap sync", null, List.of(), false);

        given().when()
                .get("/booking/" + token + "/cancel")
                .then()
                .statusCode(200)
                .body(containsString("Roadmap sync")); // effectiveTitle, not the type name
    }

    // The app validates title/description by CHARACTER count (<= 2000), but Quarkus limits each form
    // attribute by BYTE size (quarkus.http.limits.max-form-attribute-size). With the 2048-byte default,
    // a multibyte description within the 2000-char cap exceeds 2048 bytes and was rejected with an opaque
    // HTTP 413 before ever reaching the 422 validator. The limit is raised so app validation is authoritative.

    @Test
    void multibyteDescriptionWithinCharLimitIsAcceptedNot413() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();
        // 2000 Hebrew chars = 4000 UTF-8 bytes: within the 2000-char app cap, but ~2x the old 2048-byte limit.
        var desc = "א".repeat(2000);

        // Force UTF-8 form encoding so the multibyte bytes on the wire match a real browser POST from our
        // UTF-8 pages (RestAssured's default content charset would mangle Hebrew to '?').
        given().config(RestAssured.config().encoderConfig(encoderConfig().defaultContentCharset("UTF-8")))
                .contentType("application/x-www-form-urlencoded")
                .formParam("title", "Kickoff")
                .formParam("description", desc)
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(200); // reaches the app + passes validation, not a raw 413

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findByManageToken(token));
        assertEquals(desc, after.description);
    }

    @Test
    void overlongMultibyteDescriptionGives422Not413() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();
        // 2001 Hebrew chars = 4002 bytes: over BOTH the old byte limit and the char cap. Must surface the
        // app's clean 422 ("Description is too long."), not the transport-layer 413.
        var desc = "א".repeat(2001);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("description", desc)
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(422);
    }
}
