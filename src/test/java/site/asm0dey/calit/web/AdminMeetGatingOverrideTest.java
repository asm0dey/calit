package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

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
        // Not a bare 400: the Host lands back on the detail page with a localized message and a
        // usable form, and nothing is persisted (calit-w7gq).
        editWithLocation(typeId, "GOOGLE_MEET")
                .statusCode(200)
                // Qute HTML-escapes the apostrophe in "can't" (renders as "can&#39;t"); assert on
                // the unescaped tail of the message instead of fighting the entity encoding.
                .body(containsString("create Google Meet links"));

        MeetingType t = MeetingType.findById(typeId);
        org.junit.jupiter.api.Assertions.assertEquals(MeetingType.LocationType.PHONE, t.locationType);
    }

    @Test
    void meetRejectionIsLocalized() {
        var typeId = seed(true, false);
        // Owner-scoped routes resolve locale from OwnerSettings, not the calit_lang cookie
        // (LocaleResolutionFilter) -- set it the same way AdminI18nTest does.
        given().cookie("quarkus-credential", FormAuth.login())
                .formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "de")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Meet override")
                .formParam("slug", "meet-override-" + typeId)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types/" + typeId + "/edit")
                .then()
                .statusCode(200)
                .body(containsString("keine Google-Meet-Links erstellen"));
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
