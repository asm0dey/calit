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

@QuarkusTest
class OwnerManageBookingTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    /** Seeds an auto (no-approval) PHONE meeting and books it → CONFIRMED. Returns its id. */
    @Transactional
    Long seedConfirmedBooking() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        var slug = "owner-manage-" + System.nanoTime();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Owner Manage Type";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
        t.requiresApproval = false;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow;
            r.startTime = LocalTime.of(9, 0);
            r.endTime = LocalTime.of(17, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        Booking b = bookingService.book(
                1L,
                slug,
                slot.start().toInstant(),
                "Pat",
                "pat@example.com",
                java.util.Map.of(),
                "",
                "",
                "en",
                List.of());
        return b.id; // CONFIRMED (no approval)
    }

    @Test
    void managePageShowsRescheduleAndCancelForOwnBooking() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seedConfirmedBooking();

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/" + id + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/me/bookings/" + id + "/reschedule"))
                .body(containsString("/me/bookings/" + id + "/cancel"))
                .body(containsString("Pat"));
    }

    @Test
    void managePageReturns404ForUnknownBooking() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/999999/manage")
                .then()
                .statusCode(404);
    }

    @Test
    void managePageRequiresAuth() {
        given().redirects()
                .follow(false)
                .when()
                .get("/me/bookings/1/manage")
                .then()
                .statusCode(302);
    }
}
