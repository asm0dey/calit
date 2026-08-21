package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCalendar;
import site.asm0dey.calit.google.GoogleCredential;

/**
 * The GOOGLE_MEET gate follows the calendar the type actually writes on: a Meet-capable override
 * unblocks a type whose owner default cannot Meet, and a non-Meet override blocks one whose default
 * can.
 */
@QuarkusTest
class AdminMeetGatingOverrideTest {

    @AfterEach
    @Transactional
    void cleanup() {
        MeetingType.delete("slug like ?1", "meet-override-%");
        GoogleCalendar.deleteAll();
        GoogleCredential.deleteAll();
    }

    @Test
    void meetAllowedWhenTheTypeOverridesToAMeetCapableCalendar() {
        var typeId = seed(false, true); // default cannot Meet, override can
        editWithLocation(typeId, "GOOGLE_MEET").statusCode(200);
    }

    @Test
    void meetRejectedWhenTheTypeOverridesToANonMeetCalendar() {
        var typeId = seed(true, false); // default can Meet, override cannot
        editWithLocation(typeId, "GOOGLE_MEET").statusCode(400);
    }

    private io.restassured.response.ValidatableResponse editWithLocation(Long typeId, String locationType) {
        return given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Meet override")
                .formParam("slug", "meet-override-" + typeId)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", locationType)
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + typeId + "/edit")
                .then();
    }

    /** Owner 1 gets a write target + a second calendar, and a type overriding onto the second. */
    @Transactional
    Long seed(boolean defaultSupportsMeet, boolean overrideSupportsMeet) {
        GoogleCredential c = new GoogleCredential();
        c.ownerId = 1L;
        c.refreshToken = "rt";
        c.googleSub = "sub-meet-override-" + UUID.randomUUID();
        c.persist();
        persistCalendar(c.id, "default@example.com", true, defaultSupportsMeet);
        persistCalendar(c.id, "override@example.com", false, overrideSupportsMeet);

        MeetingType t = new MeetingType();
        t.ownerId = 1L;
        t.name = "Meet override";
        t.slug = "meet-override-" + UUID.randomUUID();
        t.durationMinutes = 30;
        t.locationType = MeetingType.LocationType.PHONE;
        t.googleCredentialId = c.id;
        t.googleCalendarId = "override@example.com";
        t.persist();
        return t.id;
    }

    private static void persistCalendar(Long credId, String calId, boolean writeTarget, boolean meet) {
        GoogleCalendar g = new GoogleCalendar();
        g.ownerId = 1L;
        g.googleCredentialId = credId;
        g.googleCalendarId = calId;
        g.summary = calId;
        g.readForBusy = writeTarget;
        g.writeTarget = writeTarget;
        g.supportsMeet = meet;
        g.persist();
    }
}
