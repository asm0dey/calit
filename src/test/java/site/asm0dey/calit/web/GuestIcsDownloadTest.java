package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

// #195: IcsBuilder output only ever left the app as a mail attachment, so a failed send took the
// guest's calendar entry with it. The confirmation page now serves it directly.
@QuarkusTest
class GuestIcsDownloadTest {

    @Test
    void confirmationPageOffersTheIcsDownload() {
        GuestBookingFixture.book(this.getClass().getSimpleName() + "-link")
                .then()
                .statusCode(200)
                .body(containsString("/invite.ics"))
                .body(containsString("Add to your calendar"));
    }

    @Test
    void icsEndpointServesTheCalendarEntry() {
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
    void unknownTokenIs404() {
        given().when().get("/booking/not-a-real-token/invite.ics").then().statusCode(404);
    }
}
