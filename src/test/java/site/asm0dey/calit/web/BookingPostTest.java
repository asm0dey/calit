package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.BookingField;
import site.asm0dey.calit.domain.BookingField.FieldType;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class BookingPostTest {

    @InjectMock
    CalendarPort calendarPort;

    Long seededOwnerId;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) { owner = AppUser.create("bob", "x", false); owner.persistAndFlush(); } // create() builds but does not persist; flush to assign id
        Long ownerId = owner.id;
        seededOwnerId = ownerId;
        // Idempotent across the multiple seed() calls in this class (committed tx, fixed slug).
        // Bookings carry an FK to meeting_type, so clear them (committed by earlier tests) first.
        site.asm0dey.calit.booking.Booking.delete(
                "meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)", "confirm-type", ownerId);
        BookingField.delete("meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)", "confirm-type", ownerId);
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "confirm-type");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Confirm Type"; t.slug = "confirm-type"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; // auto type, Meet link
        t.persist();

        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }

        // A required custom field — its answer must flow through to BookingService.book.
        BookingField f = new BookingField();
        f.ownerId = ownerId;
        f.meetingTypeId = t.id; f.fieldKey = "company"; f.label = "Company Name";
        f.type = FieldType.SHORT_TEXT; f.required = true; f.position = 0;
        f.persist();
    }

    /** An approval-requiring type → book(...) returns PENDING with no Meet link. */
    @Transactional
    void seedApprovalType() {
        AppUser owner = AppUser.findByUsername("bob");
        if (owner == null) { owner = AppUser.create("bob", "x", false); owner.persistAndFlush(); } // create() builds but does not persist; flush to assign id
        Long ownerId = owner.id;
        site.asm0dey.calit.booking.Booking.delete(
                "meetingTypeId in (select id from MeetingType where slug = ?1 and ownerId = ?2)", "approval-confirm", ownerId);
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "approval-confirm");
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) { s = new OwnerSettings(); s.ownerId = ownerId; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "Approval Confirm Type"; t.slug = "approval-confirm"; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    private String firstSlot(String slug) {
        String html = given().when().get("/bob/" + slug).then().statusCode(200)
                .extract().asString();
        String startUtc = html.substring(
                html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return startUtc.substring(0, startUtc.indexOf('"'));
    }

    @Test
    void postCreatesBookingAndShowsMeetLinkAndManageToken() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        // createEvent returns a known Meet link that BookingService stores on Booking.meetLink.
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
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
            .when().post("/bob/confirm-type")
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
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seedApprovalType();

        String chosen = firstSlot("approval-confirm");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", chosen)
            .formParam("inviteeName", "Pat Requester")
            .formParam("inviteeEmail", "pat@example.com")
            .formParam("website", "")
            .when().post("/bob/approval-confirm")
            .then()
                .statusCode(200)
                // PENDING booking → request-sent wording, NOT "You're booked" / a Meet link.
                .body(containsString("Request sent — pending owner approval"))
                .body(not(containsString("You're booked")))
                .body(not(containsString("meet.google.com")));
    }

    @Test
    void postWithFilledHoneypotIsRejected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
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
            .when().post("/bob/confirm-type")
            .then()
                .statusCode(200)
                .body(containsString("alert-error"))         // inline rejection message
                .body(not(containsString("You're booked"))); // no confirmation
    }

    @Test
    void postWithMissingRequiredAnswerReRendersFormWith422NotServerError() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        // Omit the required "answers.company" → BookingService.book throws the 422 validation
        // exception; the handler must re-render the booking form with an inline error, NOT 500.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", firstSlot("confirm-type"))
            .formParam("inviteeName", "Sam Invitee")
            .formParam("inviteeEmail", "sam@example.com")
            .formParam("website", "")
            .when().post("/bob/confirm-type")
            .then()
                .statusCode(200)
                .body(containsString("alert-error"))     // inline validation message rendered
                .body(containsString("name=\"startUtc\"")); // back on the booking form, not a 500
    }

    @Test
    void postSetsBookingOwnerIdFromResolvedUser() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-owner", "https://meet.google.com/owned",
                                         "https://calendar.google.com/evt-owner"));
        seed();

        String chosen = firstSlot("confirm-type");
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("startUtc", chosen)
            .formParam("inviteeName", "Owned Booking")
            .formParam("inviteeEmail", "owned@example.com")
            .formParam("answers.company", "Acme Corp")
            .formParam("website", "")
            .when().post("/bob/confirm-type")
            .then().statusCode(200);

        Booking b = Booking.find("inviteeEmail = ?1 and status <> ?2",
                "owned@example.com", BookingStatus.CANCELLED).firstResult();
        org.junit.jupiter.api.Assertions.assertNotNull(b, "booking must be created");
        org.junit.jupiter.api.Assertions.assertEquals(seededOwnerId, b.ownerId,
                "Booking.ownerId must be the resolved /{user} owner");
    }
}
