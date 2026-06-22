package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.BookingStatus;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class AdminPendingTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    /** Seeds an approval-type meeting and books it → the booking lands PENDING. Returns its id. */
    @Transactional
    Long seedPendingBooking() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        // Unique slug per invocation: each @Test commits its seed, so a fixed slug would
        // collide on the meeting_type unique-slug constraint across tests (Plan 5 isolation).
        String slug = "pending-queue-" + System.nanoTime();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Pending Queue Type"; t.slug = slug; t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET; t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(),
                now().plusDays(14)).getFirst();
        Booking b = bookingService.book(1L, slug, slot.start().toInstant(),
                "Pending Pat", "pat@example.com", java.util.Map.of(), "", "", "en");
        return b.id; // status == PENDING
    }

    @Test
    void pendingQueueListsPendingBookingWithApproveDeclineForms() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-p", "https://meet.google.com/pending", "h"));
        Long id = seedPendingBooking();

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/pending")
            .then()
                .statusCode(200)
                .body(containsString("Pending Pat"))                       // the PENDING booking
                .body(containsString("/me/bookings/" + id + "/approve")) // approve form
                .body(containsString("/me/bookings/" + id + "/decline")) // decline form
                .body(containsString("Approve"))
                .body(containsString("Decline"));
    }

    @Test
    void approveConfirmsTheBooking() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
            .thenReturn(new CreatedEvent("evt-a", "https://meet.google.com/approved", "h"));
        Long id = seedPendingBooking();

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .when().post("/me/bookings/" + id + "/approve")
            .then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals(
                BookingStatus.CONFIRMED, ((Booking) Booking.findById(id)).status);
    }

    @Test
    void declineMarksTheBookingDeclined() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        Long id = seedPendingBooking();

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .when().post("/me/bookings/" + id + "/decline")
            .then().statusCode(200);

        org.junit.jupiter.api.Assertions.assertEquals(
                BookingStatus.DECLINED, ((Booking) Booking.findById(id)).status);
    }

    @Test
    void pendingPageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/pending").then().statusCode(302);
    }
}
