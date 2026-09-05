package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Inject
    site.asm0dey.calit.booking.BookingService bookingService;

    // --- Unit E (@antigravity-wanderer): hideGuests behaviour ---

    // 1. Field absent from the markup when hideGuests = true
    @Test
    void bookingFormHidesGuestsFieldWhenHideGuestsOn() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.find("slug", "g-type").firstResult();
            t.hideGuests = true;
            t.persist();
        });

        given().when()
                .get("/gob/g-type")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.not(containsString("name=\"guests\"")));
    }

    // 2. Crafted booking POST with guests attaches none when the flag is on
    @Test
    void craftedBookingPostWithGuestsAttachesZeroWhenHideGuestsOn() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.find("slug", "g-type").firstResult();
            t.hideGuests = true;
            t.persist();
        });
        mailbox.clear();

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Eve")
                .formParam("inviteeEmail", "eve@example.com")
                .formParam("website", "")
                .formParam("guests", "crafted1@example.com, crafted2@example.com")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "eve@example.com").firstResult();
        assertNotNull(b);
        assertEquals(0, BookingGuest.activeForBooking(b.id).size(), "Guard must drop guests on booking");
        assertEquals(0, mailbox.getMailsSentTo("crafted1@example.com").size());
        assertEquals(0, mailbox.getMailsSentTo("crafted2@example.com").size());
    }

    // 3. Crafted manage POST (/edit-details) ignores guests when the flag is on
    @Test
    void craftedManageEditDetailsWithGuestsAttachesZeroWhenHideGuestsOn() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.find("slug", "g-type").firstResult();
            t.hideGuests = true;
            t.persist();
        });

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Dave")
                .formParam("inviteeEmail", "dave@example.com")
                .formParam("website", "")
                .formParam("guests", "")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "dave@example.com").firstResult();
        assertNotNull(b);
        String token = b.manageToken;

        given().when()
                .get("/booking/" + token + "/manage")
                .then()
                .statusCode(200)
                .body(org.hamcrest.Matchers.not(containsString("name=\"guests\"")));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Updated title")
                .formParam("description", "Updated desc")
                .formParam("guests", "sneakyguest@example.com")
                .when()
                .post("/booking/" + token + "/edit-details")
                .then()
                .statusCode(200);

        Booking after = QuarkusTransaction.requiringNew().call(() -> Booking.findByManageToken(token));
        assertEquals("Updated title", after.title);
        assertEquals(0, BookingGuest.activeForBooking(after.id).size(), "Manage guard must drop guests");
    }

    // 4. Owner may add guests, and an invitee edit must not wipe them (Unit F)
    @Test
    void ownerCanAddGuestsOnHiddenGuestsBookingAndInviteeEditDoesNotWipeThem() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed();
        QuarkusTransaction.requiringNew().run(() -> {
            MeetingType t = MeetingType.find("slug", "g-type").firstResult();
            t.hideGuests = true;
            t.persist();
        });

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "Alice")
                .formParam("inviteeEmail", "alice@example.com")
                .formParam("website", "")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "alice@example.com").firstResult();
        assertNotNull(b);
        assertEquals(0, BookingGuest.activeForBooking(b.id).size());

        QuarkusTransaction.requiringNew()
                .run(() -> bookingService.updateDetails(
                        b.manageToken, "Owner Sync", "Notes", List.of("vip@example.com"), true));

        List<BookingGuest> guestsAfterOwner = BookingGuest.activeForBooking(b.id);
        assertEquals(1, guestsAfterOwner.size(), "Owner must be allowed to add guests");
        assertEquals("vip@example.com", guestsAfterOwner.getFirst().email);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Invitee New Title")
                .formParam("description", "Invitee New Desc")
                .when()
                .post("/booking/" + b.manageToken + "/edit-details")
                .then()
                .statusCode(200);

        List<BookingGuest> guestsAfterInviteeEdit = BookingGuest.activeForBooking(b.id);
        assertEquals(1, guestsAfterInviteeEdit.size(), "Existing guest must NOT be wiped by invitee edit");
        assertEquals("vip@example.com", guestsAfterInviteeEdit.getFirst().email);
    }

    // 5. Positive control (@glitchfox's rule): with the flag OFF, guests attach normally
    @Test
    void positiveControlGuestsAttachWhenHideGuestsOff() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        seed(); // hideGuests = false

        given().when().get("/gob/g-type").then().statusCode(200).body(containsString("name=\"guests\""));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("startUtc", firstSlot())
                .formParam("inviteeName", "NormalUser")
                .formParam("inviteeEmail", "normal@example.com")
                .formParam("website", "")
                .formParam("guests", "pos1@example.com, pos2@example.com")
                .when()
                .post("/gob/g-type")
                .then()
                .statusCode(200);

        Booking b = Booking.find("inviteeEmail", "normal@example.com").firstResult();
        assertNotNull(b);
        assertEquals(2, BookingGuest.activeForBooking(b.id).size(), "Positive control: guests attach when flag off");

        given().contentType("application/x-www-form-urlencoded")
                .formParam("title", "Normal Edit")
                .formParam("description", "Normal Desc")
                .formParam("guests", "pos1@example.com, pos3@example.com")
                .when()
                .post("/booking/" + b.manageToken + "/edit-details")
                .then()
                .statusCode(200);

        List<BookingGuest> active = BookingGuest.activeForBooking(b.id);
        assertEquals(2, active.size());
        var emails = active.stream().map(g -> g.email).collect(java.util.stream.Collectors.toSet());
        org.junit.jupiter.api.Assertions.assertTrue(
                emails.contains("pos1@example.com") && emails.contains("pos3@example.com"));
    }
}
