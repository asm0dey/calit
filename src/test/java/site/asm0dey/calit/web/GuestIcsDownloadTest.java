package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.google.CalendarPort;
import site.asm0dey.calit.google.CreatedEvent;

// #195: IcsBuilder output only ever left the app as a mail attachment, so a failed send took the
// guest's calendar entry with it. The confirmation page now serves it directly -- but only where
// calit owns the invite. With Google connected, Google invites the guest natively and owns the
// event; an imported copy carries a different UID, so the next reschedule or cancel would update
// Google's event and strand the imported one. The page therefore hides the LINK when Google is
// connected while the endpoint stays open to anyone holding the manage token.
@QuarkusTest
class GuestIcsDownloadTest {

    // The port is mocked rather than left to the real GoogleCalendarPort so each test states which
    // branch it is on: unstubbed, Mockito's isConnected returns false, which is also what the real
    // port returns in %test (no google_credential rows are seeded) -- but "false because we said so"
    // is the assertion we want, not "false because nobody happened to connect Google".
    @InjectMock
    CalendarPort calendarPort;

    @Test
    void confirmationPageOffersTheIcsDownloadWhenGoogleIsNotConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        GuestBookingFixture.book(this.getClass().getSimpleName() + "-link")
                .then()
                .statusCode(200)
                .body(containsString("/invite.ics"))
                .body(containsString("Add to your calendar"));
    }

    @Test
    void confirmationPageHidesTheIcsDownloadWhenGoogleIsConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        when(calendarPort.freeBusy(anyLong(), any(), any())).thenReturn(List.of());
        when(calendarPort.createEvent(
                        anyLong(), any(), anyString(), anyString(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new CreatedEvent("evt-ics-hidden", null, "h", null));

        // Both substrings appear on the disconnected branch above, so neither negative guard can
        // pass just because the page changed shape: the href and the button label are the offer.
        GuestBookingFixture.book(this.getClass().getSimpleName() + "-hidden")
                .then()
                .statusCode(200)
                .body(not(containsString("/invite.ics")))
                .body(not(containsString("Add to your calendar")));
    }

    @Test
    void icsEndpointServesTheCalendarEntry() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);

        String token = GuestBookingFixture.manageTokenOf(
                GuestBookingFixture.book(this.getClass().getSimpleName() + "-dl"));

        given().when()
                .get("/booking/" + token + "/invite.ics")
                .then()
                .statusCode(200)
                .contentType(containsString("text/calendar"))
                .header("Content-Disposition", containsString("invite.ics"))
                .body(containsString("BEGIN:VCALENDAR"))
                .body(containsString("BEGIN:VEVENT"))
                .body(containsString("UID:" + token));
    }

    @Test
    void icsEndpointStaysOpenWhenGoogleIsConnected() {
        when(calendarPort.isConnected(anyLong())).thenReturn(false);
        String token = GuestBookingFixture.manageTokenOf(
                GuestBookingFixture.book(this.getClass().getSimpleName() + "-open"));

        // Only the OFFER is conditional. A guest who kept the URL (or connects Google after
        // booking) still gets the file -- the route is authorized by the manage token alone.
        when(calendarPort.isConnected(anyLong())).thenReturn(true);
        given().when()
                .get("/booking/" + token + "/invite.ics")
                .then()
                .statusCode(200)
                .body(containsString("UID:" + token));
    }

    @Test
    void unknownTokenIs404() {
        given().when().get("/booking/not-a-real-token/invite.ics").then().statusCode(404);
    }
}
