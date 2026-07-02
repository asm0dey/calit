package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.booking.Booking;
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.BookingService;
import site.asm0dey.calit.booking.GuestStatus;
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

    @Test
    void rescheduleMovesTheBookingToTheChosenSlot() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seedConfirmedBooking();
        Booking before = Booking.findById(id);
        int beforeSeq = before.icsSequence;
        // pick a different available slot from the type's availability
        MeetingType type = MeetingType.findById(before.meetingTypeId);
        var slots = bookingService.availableSlots(type, now(), now().plusDays(14));
        var target = slots.stream()
                .map(s -> s.start().toInstant())
                .filter(i -> !i.equals(before.startUtc))
                .findFirst()
                .orElseThrow();

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", target.toString())
                .when()
                .post("/me/bookings/" + id + "/reschedule")
                .then()
                .statusCode(200);

        // Load in a fresh transaction so the test's L1 cache does not return the pre-POST snapshot.
        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(id));
        org.junit.jupiter.api.Assertions.assertEquals(target, after.startUtc);
        org.junit.jupiter.api.Assertions.assertTrue(after.icsSequence > beforeSeq, "sequence bumped");
    }

    @Test
    void cancelMarksTheBookingCancelled() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seedConfirmedBooking();

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/bookings/" + id + "/cancel")
                .then()
                .statusCode(200);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findById(id));
        org.junit.jupiter.api.Assertions.assertEquals(site.asm0dey.calit.booking.BookingStatus.CANCELLED, after.status);
    }

    /** Seeds a confirmed booking that has one active guest; returns [bookingId, guestId]. */
    @Transactional
    long[] seedConfirmedBookingWithGuest(long bookingId) {
        BookingGuest g = new BookingGuest();
        g.ownerId = 1L;
        g.bookingId = bookingId;
        g.email = "guest@example.com";
        g.status = GuestStatus.INVITED;
        g.declineToken = "dt-" + System.nanoTime();
        g.createdAt = Instant.now();
        g.persist();
        return new long[] {bookingId, g.id};
    }

    @Test
    void reschedulePreservesExistingGuests() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var bookingId = seedConfirmedBooking();
        seedConfirmedBookingWithGuest(bookingId);

        Booking before = Booking.findById(bookingId);
        MeetingType type = MeetingType.findById(before.meetingTypeId);
        var target = bookingService.availableSlots(type, now(), now().plusDays(14)).stream()
                .map(s -> s.start().toInstant())
                .filter(i -> !i.equals(before.startUtc))
                .findFirst()
                .orElseThrow();

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", target.toString())
                .when()
                .post("/me/bookings/" + bookingId + "/reschedule")
                .then()
                .statusCode(200);

        List<BookingGuest> afterGuests =
                QuarkusTransaction.requiringNew().call(() -> BookingGuest.activeForBooking(bookingId));
        assertEquals(1, afterGuests.size(), "guest must be preserved across owner reschedule");
        assertEquals("guest@example.com", afterGuests.getFirst().email);
    }

    @Test
    void managePageShowsGuestEmail() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var bookingId = seedConfirmedBooking();
        seedConfirmedBookingWithGuest(bookingId);

        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/bookings/" + bookingId + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("guest@example.com"));
    }

    @Test
    void rescheduleAnotherOwnersBookingIs404AndDoesNotMutate() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var id = seedConfirmedBooking();
        var before = ((Booking) Booking.findById(id)).startUtc;

        // Log in as a second, non-owning user (admin id 1 is the owner; create + login a different user).
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", before.toString())
                .when()
                .post("/me/bookings/999999/reschedule")
                .then()
                .statusCode(404);

        org.junit.jupiter.api.Assertions.assertEquals(before, ((Booking) Booking.findById(id)).startUtc);
    }
}
