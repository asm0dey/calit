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
import site.asm0dey.calit.google.CreatedEvent;

@QuarkusTest
class InviteeEditDetailsTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    BookingService bookingService;

    @Transactional
    String seed() {
        Booking.delete("meetingTypeId in (select id from MeetingType where slug = ?1)", "invitee-edit");
        MeetingType.delete("slug", "invitee-edit");
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
        t.name = "Invitee Edit Type";
        t.slug = "invitee-edit";
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
        var slot = bookingService.availableSlots(t, now(), now().plusDays(14)).getFirst();
        return bookingService.book(
                        1L,
                        "invitee-edit",
                        slot.start().toInstant(),
                        "Pat",
                        "pat@example.com",
                        java.util.Map.of(),
                        "",
                        "",
                        "en",
                        List.of())
                .manageToken;
    }

    @Test
    void managePageShowsEditDetailsForm() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(anyLong(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ie", "https://meet.google.com/ie", "h"));
        var token = seed();
        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(containsString("/booking/" + token + "/edit-details"))
                .body(containsString("name=\"title\""))
                .body(containsString("name=\"description\""));
    }

    @Test
    void inviteeEditsNameDescriptionAndGuestThenSeesHub() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Roadmap sync")
                .formParam("description", "Q3 planning")
                .formParam("guests", "ana@example.com")
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(200)
                .body(containsString("value=\"Roadmap sync\""))
                .body(containsString("/booking/" + token + "/edit-details"));

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findByManageToken(token));
        assertEquals("Roadmap sync", after.title);
        assertEquals("Q3 planning", after.description);
    }

    @Test
    void cancelConfirmPageShowsEditedName() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        var token = seed();
        bookingService.updateDetails(token, "Roadmap sync", null, List.of(), false);

        given().when()
                .get("/booking/" + token + "/cancel")
                .then()
                .statusCode(200)
                .body(containsString("Roadmap sync")); // effectiveTitle, not the type name
    }
}
