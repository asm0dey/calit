package site.asm0dey.calit.web;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
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
import site.asm0dey.calit.user.AppUser;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        var slots = bookingService.availableSlots(t, now(), now().plusDays(14));
        assertFalse(slots.isEmpty(), "no available slots seeded — check AvailabilityRule setup");
        var slot = slots.get(0);
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

    /**
     * Seeds a PENDING approval booking owned by a SECOND user ("ownerb-approval"), not admin.
     * Persists the Booking row directly (no bookingService.book) to avoid needing full OwnerSettings
     * for the second user's email flows. Pattern mirrors CrossOwnerIsolationTest.seedOwnerB().
     */
    @Transactional
    BookingRef newPendingBookingForSecondOwner() {
        AppUser b = AppUser.findByUsername("ownerb-approval");
        if (b == null) {
            b = AppUser.create("ownerb-approval", "x", false);
            b.enabled = true;
            b.persist();
        }

        if (OwnerSettings.forOwner(b.id) == null) {
            OwnerSettings s = new OwnerSettings();
            s.ownerId = b.id; s.ownerName = "Owner B Approval"; s.ownerEmail = "ownerb-approval@example.com";
            s.timezone = "UTC";
            s.persist();
        }

        MeetingType t = MeetingType.<MeetingType>find("ownerId = ?1 and slug = ?2", b.id, "approval-b-type").firstResult();
        if (t == null) {
            t = new MeetingType();
            t.ownerId = b.id; t.name = "B Approval Type"; t.slug = "approval-b-type";
            t.durationMinutes = 30; t.requiresApproval = true;
            t.persist();
        }

        // Persist booking row directly — simpler than going through bookingService for a foreign owner.
        final Long typeId = t.id;
        final Long ownerId = b.id;
        Booking bk = Booking.<Booking>find("ownerId = ?1 and meetingTypeId = ?2 and status = ?3",
                ownerId, typeId, BookingStatus.PENDING).firstResult();
        if (bk == null) {
            bk = new Booking();
            bk.ownerId = ownerId; bk.meetingTypeId = typeId;
            bk.inviteeName = "B Guest"; bk.inviteeEmail = "bguest@example.com";
            bk.startUtc = Instant.now().plusSeconds(86400);
            bk.endUtc = bk.startUtc.plusSeconds(1800);
            bk.createdAt = Instant.now();
            bk.manageToken = UUID.randomUUID().toString();
            bk.approvalToken = UUID.randomUUID().toString();
            bk.status = BookingStatus.PENDING;
            bk.persist();
        }

        Booking loaded = Booking.findById(bk.id);
        return new BookingRef(loaded.id, loaded.approvalToken);
    }

    /** Admin (owner A) must get 404 when trying to approve a booking owned by a different user. */
    @Test
    void crossOwnerApproveIs404() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBookingForSecondOwner();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/approve?t=" + b.approvalToken)
                .then().statusCode(404);
    }

    /** Admin (owner A) must get 404 when trying to decline a booking owned by a different user. */
    @Test
    void crossOwnerDeclineIs404() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        var b = newPendingBookingForSecondOwner();
        given().spec(authedAdmin())
                .when().get("/me/bookings/" + b.id + "/decline?t=" + b.approvalToken)
                .then().statusCode(404);
    }
}
