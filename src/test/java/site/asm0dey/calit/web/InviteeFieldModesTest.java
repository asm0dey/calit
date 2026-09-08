package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.FieldMode;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

/** GH #130: per-type name/guests field modes on the booking form, enforced server-side. */
@QuarkusTest
class InviteeFieldModesTest {

    @InjectMock
    CalendarPort calendarPort;

    @BeforeEach
    void stubCalendar() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
    }

    @Transactional
    Long seed(FieldMode nameMode, FieldMode guestsMode) {
        AppUser owner = AppUser.findByUsername("modes");
        if (owner == null) {
            owner = AppUser.create("modes", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        Booking.delete("ownerId", ownerId);
        BookingGuest.delete("ownerId", ownerId);
        MeetingType.delete("ownerId", ownerId);
        AvailabilityRule.delete("ownerId", ownerId);
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Modes";
        t.slug = "modes";
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.nameMode = nameMode;
        t.guestsMode = guestsMode;
        t.persist();
        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(0, 0);
            r.endTime = LocalTime.of(23, 59);
            r.persist();
        }
        return t.id;
    }

    private String firstSlot() {
        String html = given().when()
                .get("/modes/modes")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        var s = html.substring(html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return s.substring(0, s.indexOf('"'));
    }

    private int post(String name, String guests) {
        var req = given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "");
        if (name != null) {
            req.formParam("inviteeName", name);
        }
        if (guests != null) {
            req.formParam("guests", guests);
        }
        return req.when().post("/modes/modes").then().extract().statusCode();
    }

    private static Booking booked() {
        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        assertNotNull(b);
        return b;
    }

    @Test
    void defaultsRenderRequiredNameAndGuests() {
        seed(FieldMode.REQUIRED, FieldMode.OPTIONAL);
        given().when()
                .get("/modes/modes")
                .then()
                .statusCode(200)
                .body(containsString("name=\"inviteeName\" required"))
                .body(containsString("name=\"guests\""));
    }

    @Test
    void hiddenModesDropBothInputs() {
        seed(FieldMode.HIDDEN, FieldMode.HIDDEN);
        given().when()
                .get("/modes/modes")
                .then()
                .statusCode(200)
                .body(not(containsString("name=\"inviteeName\"")))
                .body(not(containsString("name=\"guests\"")));
    }

    @Test
    void optionalNameRendersWithoutRequired() {
        seed(FieldMode.OPTIONAL, FieldMode.OPTIONAL);
        given().when()
                .get("/modes/modes")
                .then()
                .statusCode(200)
                .body(containsString("name=\"inviteeName\">"))
                .body(not(containsString("name=\"inviteeName\" required")));
    }

    @Test
    void hiddenTypeIgnoresSubmittedNameAndGuests() {
        seed(FieldMode.HIDDEN, FieldMode.HIDDEN);
        assertEquals(200, post("Sam", "ana@example.com, bob@example.com"));
        Booking b = booked();
        assertEquals("sam", b.inviteeName);
        assertEquals(0, BookingGuest.activeForBooking(b.id).size());
    }

    @Test
    void blankNameOnOptionalTypeBooksWithEmailLocalPart() {
        seed(FieldMode.OPTIONAL, FieldMode.OPTIONAL);
        assertEquals(200, post("", "ana@example.com"));
        Booking b = booked();
        assertEquals("sam", b.inviteeName);
        assertEquals(1, BookingGuest.activeForBooking(b.id).size());
    }

    @Test
    void givenNameOnOptionalTypeIsKept() {
        seed(FieldMode.OPTIONAL, FieldMode.OPTIONAL);
        assertEquals(200, post("Sam", null));
        assertEquals("Sam", booked().inviteeName);
    }

    @Test
    void blankNameOnRequiredTypeIsRejected() {
        seed(FieldMode.REQUIRED, FieldMode.OPTIONAL);
        // The form handler re-renders the booking page with the validation message, so it is a 200
        // with no booking row -- the same contract every other BookingValidationException has here.
        post("", null);
        assertEquals(0, Booking.count("inviteeEmail", "sam@example.com"));
    }

    @Test
    void hiddenGuestsSurviveAnEditDetailsWithoutTheField() {
        seed(FieldMode.REQUIRED, FieldMode.OPTIONAL);
        assertEquals(200, post("Sam", "ana@example.com"));
        Booking b = booked();
        flipGuestsMode(b.meetingTypeId, FieldMode.HIDDEN);

        given().when()
                .get("/booking/" + b.manageToken + "/manage")
                .then()
                .statusCode(200)
                .body(not(containsString("name=\"guests\"")));
        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Renamed")
                .formParam("description", "")
                .when()
                .post("/booking/" + b.manageToken + "/edit-details")
                .then()
                .statusCode(200);
        // No guests field was posted, which used to mean "remove every guest"; a HIDDEN type leaves them alone.
        assertEquals(1, BookingGuest.activeForBooking(b.id).size());
    }

    @Test
    void adminFormsPersistTheModes() {
        var slug = "modes-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Modes")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("nameMode", "OPTIONAL")
                .formParam("guestsMode", "HIDDEN")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);
        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(FieldMode.OPTIONAL, t.nameMode);
        assertEquals(FieldMode.HIDDEN, t.guestsMode);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types/" + t.id)
                .then()
                .statusCode(200)
                .body(containsString("value=\"OPTIONAL\" selected"))
                .body(containsString("value=\"HIDDEN\" selected"));
    }

    @Transactional
    void flipGuestsMode(Long typeId, FieldMode mode) {
        MeetingType t = MeetingType.findById(typeId);
        t.guestsMode = mode;
    }
}
