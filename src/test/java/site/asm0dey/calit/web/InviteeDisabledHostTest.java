package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static java.time.LocalDate.now;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
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

/**
 * The token-keyed invitee routes are authenticated by an unguessable manage token, not by username,
 * so {@code PublicResource.resolveOwner}'s {@code enabled} guard never sees them. Before calit-jyck
 * an invitee holding a link from before their host was disabled could still put a NEW time on that
 * departed host's calendar and trigger a fresh notification to them.
 *
 * <p>Cancelling stays available on purpose — an invitee must always be able to get out of a meeting,
 * and cancelling a departed host's booking is exactly what you would want to happen.
 */
@QuarkusTest
class InviteeDisabledHostTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @AfterEach
    @Transactional
    void cleanup() {
        Booking.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "jyck-type");
        MeetingType.delete("slug", "jyck-type");
        AvailabilityRule.delete("ownerId", 1L);
    }

    /** A confirmed booking on owner 1, who is then disabled. Returns its manage token. */
    private String seedThenDisableHost() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-jyck", "https://meet.google.com/jyck", "h", null));
        var token = seedBooking();
        setHostEnabled(false);
        return token;
    }

    @Transactional
    String seedBooking() {
        OwnerSettings s = OwnerSettings.forOwner(1L);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = 1L;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Jyck Type";
        t.slug = "jyck-type";
        t.durationMinutes = 60;
        t.locationType = LocationType.GOOGLE_MEET;
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
        var slot = bookingService
                .availableSlots(MeetingType.findById(t.id), now(), now().plusDays(14))
                .getFirst();
        return bookingService.book(
                        1L,
                        "jyck-type",
                        slot.start().toInstant(),
                        "Pat",
                        "pat@example.com",
                        Map.of(),
                        "",
                        "",
                        "en",
                        List.of())
                .manageToken;
    }

    @Transactional
    void setHostEnabled(boolean enabled) {
        AppUser.<AppUser>findById(1L).enabled = enabled;
    }

    /** A free slot on the same type, different from the booking's current time. */
    private Instant anotherSlot(String token) {
        Booking b = Booking.findByManageToken(token);
        MeetingType t = MeetingType.findById(b.meetingTypeId);
        return bookingService.availableSlots(t, now(), now().plusDays(14)).stream()
                .map(sl -> sl.start().toInstant())
                .filter(i -> !i.equals(b.startUtc))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void manageHubHidesTheWriteFormsAndKeepsCancel() {
        var token = seedThenDisableHost();

        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("no longer taking changes"))
                // both write forms gone...
                .body(not(containsString("/booking/" + token + "/reschedule")))
                .body(not(containsString("/booking/" + token + "/edit-details")))
                // ...cancel still offered
                .body(containsString("/booking/" + token + "/cancel"));
    }

    @Test
    void rescheduleIsRefusedAndTheBookingKeepsItsTime() {
        var token = seedThenDisableHost();
        Instant before = Booking.findByManageToken(token).startUtc;
        var target = anotherSlot(token);

        // A stale tab or crafted POST: the rendered page no longer carries this form.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", target.toString())
                .when()
                .post("/booking/" + token + "/reschedule")
                .then()
                .statusCode(200)
                .body(containsString("no longer taking changes"));

        assertEquals(before, Booking.findByManageToken(token).startUtc, "the booking must not have moved");
    }

    @Test
    void editDetailsIsRefusedAndTheBookingKeepsItsDetails() {
        var token = seedThenDisableHost();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Injected title")
                .formParam("description", "Injected description")
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(200)
                .body(containsString("no longer taking changes"));

        Booking after = Booking.findByManageToken(token);
        org.junit.jupiter.api.Assertions.assertNull(after.title, "title must not have been written");
        org.junit.jupiter.api.Assertions.assertNull(after.description, "description must not have been written");
    }

    @Test
    void cancelStillWorks() {
        var token = seedThenDisableHost();

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/booking/" + token + "/cancel")
                .then()
                .statusCode(200);

        assertEquals(
                BookingStatus.CANCELLED,
                Booking.findByManageToken(token).status,
                "an invitee must always be able to cancel, even on a departed host");
    }

    @Test
    void anEnabledHostIsUnaffected() {
        // Control: the same fixture without disabling the host still offers both write forms.
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-jyck-ok", "https://meet.google.com/ok", "h", null));
        var token = seedBooking();

        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(not(containsString("no longer taking changes")))
                .body(containsString("/booking/" + token + "/reschedule"))
                .body(containsString("/booking/" + token + "/edit-details"));
    }
}
