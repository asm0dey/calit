package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
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
import site.asm0dey.calit.google.CreatedEvent;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class ApprovalLinkTest {

    @Inject
    BookingService bookingService;

    @InjectMock
    CalendarPort calendarPort;

    record BookingRef(long id, String approvalToken) {}

    /** Returns a RestAssured spec with the admin session cookie. */
    private RequestSpecification authedAdmin() {
        return given().cookie("quarkus-credential", FormAuth.login());
    }

    /** Creates a PENDING approval booking owned by admin (id 1), returns its id + approvalToken. */
    @Transactional
    BookingRef newPendingBooking() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) { s = new OwnerSettings(); s.ownerId = 1L; }
        s.ownerName = "Owner"; s.ownerEmail = "owner@example.com"; s.timezone = "Europe/Amsterdam";
        s.persist();

        // Unique slug per invocation to avoid unique-slug constraint collisions across tests.
        String slug = "approval-link-" + System.nanoTime();
        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Approval Link Type"; t.slug = slug; t.durationMinutes = 60;
        t.locationType = LocationType.PHONE; t.requiresApproval = true;
        t.persist();
        for (DayOfWeek dow : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = 1L;
            r.dayOfWeek = dow; r.startTime = LocalTime.of(9, 0); r.endTime = LocalTime.of(12, 0);
            r.meetingTypeId = null;
            r.persist();
        }
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        Booking b = bookingService.book(1L, slug, slot.start().toInstant(),
                "Test User", "test@example.com", Map.of(), "", "", "en");
        // Reload to get the persisted approvalToken
        Booking loaded = Booking.findById(b.id);
        return new BookingRef(loaded.id, loaded.approvalToken);
    }

    @Test
    void unauthenticatedApproveRedirectsToLogin() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBooking();
        given().redirects().follow(false)
                .when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test
    void authedApproveWithCorrectTokenConfirms() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBooking();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Booking approved"));
    }

    @Test
    void authedApproveWithWrongTokenIs404() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBooking();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/approve?t=wrong")
                .then().statusCode(404);
    }

    @Test
    void authedDeclineWithCorrectTokenDeclines() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBooking();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/decline?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Booking declined"));
    }

    @Test
    void secondApproveShowsAlreadyHandled() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBooking();
        given().spec(authedAdmin()).when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Booking approved"));
        given().spec(authedAdmin()).when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(200).body(containsString("Already handled"));
    }
}
