package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * GOOGLE_MEET meeting types must be impossible to set when the owner's write-target calendar can't
 * mint Meet links (Google would reject the booking with 400 "Invalid conference type value").
 * The option is hidden on the form AND rejected on submit; other location types stay allowed.
 */
@QuarkusTest
class AdminMeetGatingTest {

    // POST commits in its own request transaction, so clean the Google rows seeded here after each test.
    @AfterEach
    @Transactional
    void cleanup() {
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
    }

    @Transactional
    void seedWriteTarget(boolean supportsMeet) {
        long ownerId = 1L; // FormAuth.login() authenticates admin = owner 1
        GoogleCredential c = new GoogleCredential();
        c.ownerId = ownerId; c.refreshToken = "rt"; c.googleSub = "sub-gate";
        c.persist();
        GoogleCalendar wt = new GoogleCalendar();
        wt.ownerId = ownerId; wt.googleCredentialId = c.id; wt.googleCalendarId = "wt@example.com";
        wt.summary = "WT"; wt.readForBusy = true; wt.writeTarget = true; wt.supportsMeet = supportsMeet;
        wt.persist();
    }

    @Test
    void formHidesMeetWhenWriteTargetCannotMeet() {
        seedWriteTarget(false);
        given().cookie("quarkus-credential", FormAuth.login())
                .when().get("/me/meeting-types")
                .then().statusCode(200)
                .body(not(containsString("value=\"GOOGLE_MEET\"")));
    }

    @Test
    void formShowsMeetWhenWriteTargetSupportsMeet() {
        seedWriteTarget(true);
        given().cookie("quarkus-credential", FormAuth.login())
                .when().get("/me/meeting-types")
                .then().statusCode(200)
                .body(containsString("value=\"GOOGLE_MEET\""));
    }

    @Test
    void createMeetRejectedWhenWriteTargetCannotMeet() {
        seedWriteTarget(false);
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Blocked Meet")
                .formParam("slug", "blocked-meet-" + System.nanoTime())
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when().post("/me/meeting-types")
                .then().statusCode(400);
    }

    @Test
    void createPhoneAllowedWhenWriteTargetCannotMeet() {
        seedWriteTarget(false);
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Phone OK")
                .formParam("slug", "phone-ok-" + System.nanoTime())
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "+1-555")
                .formParam("slotIntervalMinutes", "")
                .when().post("/me/meeting-types")
                .then().statusCode(200);
    }

    @Test
    void createMeetAllowedWhenWriteTargetSupportsMeet() {
        seedWriteTarget(true);
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Meet OK")
                .formParam("slug", "meet-ok-" + System.nanoTime())
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when().post("/me/meeting-types")
                .then().statusCode(200);
    }
}
