package com.calit.web;

import com.calit.google.CalendarPort;
import com.calit.domain.AvailabilityRule;
import com.calit.domain.BookingField;
import com.calit.domain.BookingField.FieldType;
import com.calit.domain.MeetingType;
import com.calit.domain.MeetingType.LocationType;
import com.calit.domain.OwnerSettings;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookPageTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        // Idempotent across the multiple seed() calls in this class (committed tx, fixed slug).
        BookingField.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "book-page");
        MeetingType.delete("slug", "book-page");
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.name = "Book Page Type"; t.slug = "book-page"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; // auto type, Meet location
        t.persist();

        // A rule for every weekday so at least one day in the next 14 has slots.
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }

        // A required custom EXTRA field for this type — must render on the booking form.
        BookingField f = new BookingField();
        f.meetingTypeId = t.id; f.fieldKey = "company"; f.label = "Company Name";
        f.type = FieldType.SHORT_TEXT; f.required = true; f.position = 0;
        f.persist();
    }

    /** A PHONE-located, approval-requiring type to exercise features 13 + 14a wording. */
    @Transactional
    void seedApprovalPhoneType() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.name = "Phone Approval Type"; t.slug = "phone-approval"; t.durationMinutes = 60;
        t.locationType = LocationType.PHONE; t.locationDetail = "Call +1-555-0100";
        t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    @Test
    void bookPageRendersSlotsBuiltInsCustomFieldHoneypotForExistingSlug() {
        // Connected, no busy → all raw slots survive.
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        given()
            .when().get("/book/book-page")
            .then()
                .statusCode(200)
                .body(containsString("Book Page Type"))
                .body(containsString("name=\"startUtc\""))   // at least one slot radio rendered
                .body(containsString("name=\"inviteeName\"")) // built-in Full name input
                .body(containsString("name=\"inviteeEmail\"")) // built-in Email input
                .body(containsString("Company Name"))          // custom field label
                .body(containsString("name=\"answers.company\"")) // custom field input
                // Honeypot is ALWAYS present (independent of Turnstile flag) + hidden.
                .body(containsString("name=\"website\""))
                .body(containsString("style=\"display:none\""))
                // Auto (non-approval) GOOGLE_MEET type → "Confirm booking" button, Meet location line.
                .body(containsString("Confirm booking"))
                .body(containsString("Google Meet"))
                .body(not(containsString(">Request<")));
    }

    @Test
    void bookPageShowsRequestWordingAndLocationDetailForApprovalType() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seedApprovalPhoneType();

        given()
            .when().get("/book/phone-approval")
            .then()
                .statusCode(200)
                // Approval type → submit button reads "Request" (NOT "Confirm booking").
                .body(containsString(">Request<"))
                .body(not(containsString("Confirm booking")))
                // PHONE location detail shown up-front on the slot page (feature 13).
                .body(containsString("Call +1-555-0100"));
    }

    @Test
    void bookPageOmitsTurnstileWidgetWhenDisabled() {
        // Default test profile has calit.turnstile.enabled=false → no widget/script.
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        given()
            .when().get("/book/book-page")
            .then()
                .statusCode(200)
                .body(not(containsString("cf-turnstile")))
                .body(not(containsString("challenges.cloudflare.com")))
                // Honeypot is still present even with Turnstile off.
                .body(containsString("name=\"website\""));
    }

    @Test
    void bookPageCarriesViewerLocalTimezoneMachinery() {
        // The server can't run JS in @QuarkusTest, so assert the SERVER-rendered HTML
        // carries the machinery the inline script needs: per-slot data-utc absolute
        // instants, the tz-picker select, the "Times shown in" label, and the reformat script.
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        String html = given().when().get("/book/book-page")
                .then().statusCode(200).extract().asString();

        // 1) Each slot's absolute instant is rendered in a machine-readable data-utc attribute.
        //    Pull the first radio's value (an ISO-8601 instant) and assert it ALSO appears as a data-utc.
        String startUtc = html.substring(
                html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        startUtc = startUtc.substring(0, startUtc.indexOf('"'));
        org.junit.jupiter.api.Assertions.assertTrue(startUtc.endsWith("Z"),
                "slot value must be an absolute UTC instant (…Z), was: " + startUtc);
        org.junit.jupiter.api.Assertions.assertTrue(
                html.contains("data-utc=\"" + startUtc + "\""),
                "the same absolute instant must appear as a data-utc display attribute");

        given()
            .when().get("/book/book-page")
            .then()
                .statusCode(200)
                .body(containsString("id=\"tz-picker\""))     // timezone override select
                .body(containsString("CALIT_TZ_REFORMAT"))    // stable reformat-script marker
                .body(containsString("Times shown in:"))      // active-zone label element
                .body(containsString("id=\"tz-label\""))
                // The submitted value stays the absolute UTC instant — display zone never changes it.
                .body(containsString("type=\"radio\" name=\"startUtc\" value=\"" + startUtc + "\""));
    }

    @Test
    void bookPageRendersCalendarPickerAndDaySections() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        given()
            .when().get("/book/book-page")
            .then()
                .statusCode(200)
                .body(containsString("CALIT_CALENDAR"))      // enhancement script present
                .body(containsString("id=\"calendar\""))     // calendar mount point
                .body(containsString("class=\"day-slots\""))  // per-day section
                .body(containsString("name=\"startUtc\""));   // radios still posted
    }

    @Test
    void bookPageHidesMeetHintWhenGoogleNotConnected() {
        when(calendarPort.isConnected()).thenReturn(false);
        when(calendarPort.freeBusy(any(), any())).thenReturn(java.util.List.of());
        seed();
        given()
            .when().get("/book/book-page")
            .then().statusCode(200)
                .body(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("link sent after booking")));
    }

    @Test
    void bookPageReturns404ForMissingSlug() {
        given()
            .when().get("/book/does-not-exist")
            .then().statusCode(404);
    }

    @Test
    void bookPageWindowFollowsTypeHorizonNotA14DayCap() {
        // seed() gives every weekday a 9-12 rule and a type with the default horizonDays = 60.
        // The booking page must offer days well beyond two weeks (the old hardcoded 14-day cap).
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        String html = given().when().get("/book/book-page")
                .then().statusCode(200).extract().asString();

        // Collect every rendered day section's ISO date and find the furthest one.
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("data-date=\"(\\d{4}-\\d{2}-\\d{2})\"").matcher(html);
        java.time.LocalDate furthest = null;
        while (m.find()) {
            java.time.LocalDate d = java.time.LocalDate.parse(m.group(1));
            if (furthest == null || d.isAfter(furthest)) { furthest = d; }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(furthest, "expected at least one bookable day");
        // With the old 14-day cap the furthest day would be ~today+14. horizonDays=60 must extend it.
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Amsterdam"));
        org.junit.jupiter.api.Assertions.assertTrue(
                furthest.isAfter(today.plusDays(20)),
                "booking window should follow type.horizonDays (60), not a 14-day cap; furthest day was " + furthest);
    }

    @Test
    void bookPageRendersLeftInfoPanelWithHostAndDuration() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        given()
            .when().get("/book/book-page")
            .then()
                .statusCode(200)
                .body(containsString("Select a Date"))          // picker panel heading
                .body(containsString("Owner"))                  // host name from OwnerSettings
                .body(containsString("60 min"))                 // clock-icon duration line
                .body(containsString("id=\"calendar\""));       // picker still present
    }
}
