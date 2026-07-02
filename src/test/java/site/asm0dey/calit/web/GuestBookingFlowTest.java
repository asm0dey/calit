package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import io.quarkus.mailer.MockMailbox;
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
import site.asm0dey.calit.booking.BookingGuest;
import site.asm0dey.calit.booking.GuestStatus;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.MeetingType.LocationType;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class GuestBookingFlowTest {

    @InjectMock
    CalendarPort calendarPort;

    @Inject
    MockMailbox mailbox;

    @Transactional
    void seed() {
        AppUser owner = AppUser.findByUsername("gob");
        if (owner == null) {
            owner = AppUser.create("gob", "x", false);
            owner.persistAndFlush();
        }
        Long ownerId = owner.id;
        Booking.delete("ownerId", ownerId);
        BookingGuest.delete("ownerId", ownerId);
        MeetingType.delete("ownerId = ?1 and slug = ?2", ownerId, "g-type");
        AvailabilityRule.delete("ownerId", ownerId);
        OwnerSettings s = OwnerSettings.forOwner(ownerId);
        if (s == null) {
            s = new OwnerSettings();
            s.ownerId = ownerId;
        }
        s.ownerName = "Owner";
        s.ownerEmail = "owner@example.com";
        s.timezone = "Europe/Amsterdam";
        s.persist();
        MeetingType t = new MeetingType();
        t.ownerId = ownerId;
        t.name = "G Type";
        t.slug = "g-type";
        t.durationMinutes = 30;
        t.locationType = LocationType.PHONE;
        t.locationDetail = "+1 555";
        t.persist();
        for (DayOfWeek d : DayOfWeek.values()) {
            AvailabilityRule r = new AvailabilityRule();
            r.ownerId = ownerId;
            r.dayOfWeek = d;
            r.startTime = LocalTime.of(0, 0);
            r.endTime = LocalTime.of(23, 59);
            r.meetingTypeId = null;
            r.persist();
        }
    }

    private String firstSlot() {
        String html = given().when()
                .get("/gob/g-type")
                .then()
                .statusCode(200)
                .extract()
                .asString();
        var s = html.substring(html.indexOf("name=\"startUtc\" value=\"") + "name=\"startUtc\" value=\"".length());
        return s.substring(0, s.indexOf('"'));
    }

    @Test
    void bookingFormShowsGuestsField() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        given().when().get("/gob/g-type").then().statusCode(200).body(containsString("name=\"guests\""));
    }

    @Test
    void postWithGuestsCreatesGuestRowsAndEmailsThem() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        mailbox.clear();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Sam")
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "")
                .formParam("guests", "ana@example.com, bob@example.com")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        assertNotNull(b);
        assertEquals(2, BookingGuest.activeForBooking(b.id).size());
        assertEquals(1, mailbox.getMailsSentTo("ana@example.com").size());
        assertEquals(1, mailbox.getMailsSentTo("bob@example.com").size());
    }

    @Test
    void guestDeclineLinkMarksDeclinedAndNotifiesInvitee() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Sam")
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "")
                .formParam("guests", "ana@example.com")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        String token = BookingGuest.activeForBooking(b.id).getFirst().declineToken;
        mailbox.clear();

        // Confirmation page renders.
        given().when()
                .get("/guest/" + token + "/decline")
                .then()
                .statusCode(200)
                .body(containsString("Decline"));
        // POST declines (CSRF is off in %test).
        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/guest/" + token + "/decline")
                .then()
                .statusCode(200);

        // Read in a fresh session — the test thread cached the INVITED entity; the HTTP thread updated
        // it to DECLINED in a separate EntityManager. requiringNew() bypasses the stale L1 cache.
        GuestStatus finalStatus = QuarkusTransaction.requiringNew()
                .call(() -> BookingGuest.<BookingGuest>findByDeclineToken(token).status);
        assertEquals(GuestStatus.DECLINED, finalStatus);
        assertEquals(1, mailbox.getMailsSentTo("sam@example.com").size(), "invitee notified of the decline");
    }

    @Test
    @org.junit.jupiter.api.Disabled("edit-details endpoint lands in Task 7")
    void editDetailsEditsGuestList() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Sam")
                .formParam("inviteeEmail", "sam@example.com")
                .formParam("website", "")
                .formParam("guests", "ana@example.com, bob@example.com")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "sam@example.com").firstResult();
        String manageToken = b.manageToken;

        given().contentType("application/x-www-form-urlencoded")
                .formParam("guests", "ana@example.com, cyd@example.com") // drop bob, add cyd
                .when()
                .post("/booking/" + manageToken + "/edit-details")
                .then()
                .statusCode(200);

        assertEquals(GuestStatus.REMOVED, BookingGuest.findInBooking(b.id, "bob@example.com").status);
        assertEquals(2, BookingGuest.activeForBooking(b.id).size());
    }
}
