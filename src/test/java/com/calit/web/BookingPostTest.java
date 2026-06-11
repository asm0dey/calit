package com.calit.web;

import com.calit.google.CalendarPort;
import com.calit.google.CreatedEvent;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookingPostTest {

    @InjectMock
    CalendarPort calendarPort;

    @Transactional
    void seed() {
        // Idempotent across the multiple seed() calls in this class (committed tx, fixed slug).
        // Bookings carry an FK to meeting_type, so clear them (committed by earlier tests) first.
        com.calit.booking.Booking.delete(
                "meetingTypeId in (select id from MeetingType where slug = ?1)", "confirm-type");
        BookingField.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "confirm-type");
        MeetingType.delete("slug", "confirm-type");
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.name = "Confirm Type"; t.slug = "confirm-type"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; // auto type, Meet link
        t.persist();

        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }

        // A required custom field — its answer must flow through to BookingService.book.
        BookingField f = new BookingField();
        f.meetingTypeId = t.id; f.fieldKey = "company"; f.label = "Company Name";
        f.type = FieldType.SHORT_TEXT; f.required = true; f.position = 0;
        f.persist();
    }

    /** An approval-requiring type → book(...) returns PENDING with no Meet link. */
    @Transactional
    void seedApprovalType() {
        com.calit.booking.Booking.delete(
                "meetingTypeId in (select id from MeetingType where slug = ?1)", "approval-confirm");
        MeetingType.delete("slug", "approval-confirm");
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.name = "Approval Confirm Type"; t.slug = "approval-confirm"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    private String firstSlot(String slug) {
        String html = given().when().get("/book/" + slug).then().statusCode(200)
                .extract().asString();
        String startUtc = html.substring(
                html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return startUtc.substring(0, startUtc.indexOf('"'));
    }

    @Test
    void postCreatesBookingAndShowsMeetLinkAndManageToken() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        // createEvent returns a known Meet link that BookingService stores on Booking.meetLink.
        when(calendarPort.createEvent(any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-1", "https://meet.google.com/known-test-link",
                                         "https://calendar.google.com/evt-1"));
        seed();

        String chosen = firstSlot("confirm-type"); // the absolute UTC instant the invitee submits (…Z)
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", chosen)
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .formParam("answers.company", "Acme Corp") // required custom field populated
            .formParam("website", "")                  // honeypot left blank (human)
            .when().post("/book/confirm-type")
            .then()
                .statusCode(200)
                // Auto (confirmed) type → Meet link/location shown (feature 13).
                .body(containsString("https://meet.google.com/known-test-link"))
                .body(containsString("Sam Invitee"))
                // Manage link is keyed by the unguessable manageToken, NOT the numeric id.
                .body(containsString("/booking/"))
                .body(containsString("/manage"))
                // Confirmation carries viewer-local machinery: the booked instant is rendered
                // as a data-utc attribute (the SAME absolute instant the form submitted — the
                // display zone never changed which instant was booked), plus the picker + script.
                .body(containsString("data-utc=\"" + chosen + "\""))
                .body(containsString("id=\"tz-picker\""))
                .body(containsString("CALIT_TZ_REFORMAT"))
                .body(containsString("Times shown in:"));
    }

    @Test
    void postForApprovalTypeShowsPendingWordingNotConfirmation() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seedApprovalType();

        String chosen = firstSlot("approval-confirm");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", chosen)
            .formParam("inviteeName", "Pat Requester")
            .formParam("inviteeEmail", "pat@example.com")
            .formParam("website", "")
            .when().post("/book/approval-confirm")
            .then()
                .statusCode(200)
                // PENDING booking → request-sent wording, NOT "You're booked" / a Meet link.
                .body(containsString("Request sent — pending owner approval"))
                .body(not(containsString("You're booked")))
                .body(not(containsString("meet.google.com")));
    }

    @Test
    void postWithFilledHoneypotIsRejected() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        // A filled honeypot ("website") makes BookingService.book reject the submission
        // (Plan 3 abuse guard). The handler must NOT show a confirmation; it re-renders the
        // form with an inline error (HTTP 200, not a confirmation page).
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", firstSlot("confirm-type"))
            .formParam("inviteeName", "Bot")
            .formParam("inviteeEmail", "bot@example.com")
            .formParam("answers.company", "Acme Corp")
            .formParam("website", "http://spam.example")   // honeypot FILLED → bot
            .when().post("/book/confirm-type")
            .then()
                .statusCode(200)
                .body(containsString("alert-error"))         // inline rejection message
                .body(not(containsString("You're booked"))); // no confirmation
    }

    @Test
    void postWithMissingRequiredAnswerReRendersFormWith422NotServerError() {
        when(calendarPort.isConnected()).thenReturn(true);
        when(calendarPort.freeBusy(any(), any())).thenReturn(List.of());
        seed();

        // Omit the required "answers.company" → BookingService.book throws the 422 validation
        // exception; the handler must re-render the booking form with an inline error, NOT 500.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", firstSlot("confirm-type"))
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .formParam("website", "")
            .when().post("/book/confirm-type")
            .then()
                .statusCode(200)
                .body(containsString("alert-error"))     // inline validation message rendered
                .body(containsString("name=\"startUtc\"")); // back on the booking form, not a 500
    }
}
