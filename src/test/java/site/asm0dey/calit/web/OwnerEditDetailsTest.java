package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
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
class OwnerEditDetailsTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Transactional
    Long seed() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        var slug = "owner-edit-" + System.nanoTime();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Owner Edit Type";
        t.slug = slug;
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1";
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
        return bookingService.book(
                        1L,
                        slug,
                        slot.start().toInstant(),
                        "Pat",
                        "pat@example.com",
                        java.util.Map.of(),
                        "",
                        "",
                        "en",
                        List.of())
                .id;
    }

    @Test
    void managePageShowsEditDetailsForm() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed();
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/" + id + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/me/bookings/" + id + "/edit-details"))
                .body(containsString("name=\"title\""))
                .body(containsString("name=\"description\""));
    }

    @Test
    void ownerEditsNameDescriptionAndGuestThenSeesHubReRendered() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seed();

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("title", "Roadmap sync")
                .formParam("description", "Q3 planning")
                .formParam("guests", "ana@example.com")
                .when()
                .post("/me/bookings/" + id + "/edit-details")
                .then()
                .statusCode(200)
                // Re-renders the Manage hub with the saved value prefilled (raw override).
                .body(containsString("value=\"Roadmap sync\""))
                .body(containsString("/me/bookings/" + id + "/edit-details"));

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(id));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
    }

    @Test
    void editDetailsOnAnotherOwnersBookingIs404() {
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("title", "x")
                .when()
                .post("/me/bookings/999999/edit-details")
                .then()
                .statusCode(404);
    }
}
