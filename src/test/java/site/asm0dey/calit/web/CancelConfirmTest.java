package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;

import java.time.DayOfWeek;
import java.time.LocalTime;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class CancelConfirmTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    /** Seeds a CONFIRMED booking (PHONE type, no Google mock needed) and returns its manageToken. */
    @Transactional
    String newConfirmedBooking() {
        Booking.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "cancel-confirm-type");
        MeetingType.delete("slug", "cancel-confirm-type");
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Cancel Confirm Type";
        t.slug = "cancel-confirm-type";
        t.durationMinutes = 60;
        t.minNoticeMinutes = 0;
        t.horizonDays = 50_000;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1-555-000-0000";
        t.requiresApproval = false;
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
        Booking b = bookingService.book(1L, "cancel-confirm-type", slot.start().toInstant(),
                "Test Invitee", "invitee@example.com", java.util.Map.of(), "", "", "en");
        return b.manageToken;
    }

    @Test
    void cancelConfirmPageShowsConfirmForm() {
        String manageToken = newConfirmedBooking();
        given().when().get("/booking/" + manageToken + "/cancel")
                .then().statusCode(200)
                .body(containsString("/booking/" + manageToken + "/cancel")) // the POST form action
                .body(containsString("Confirm cancellation"));
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/does-not-exist/cancel")
                .then().statusCode(404);
    }
}
