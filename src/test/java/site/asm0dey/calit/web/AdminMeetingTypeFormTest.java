package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.domain.Slugs;

@QuarkusTest
class AdminMeetingTypeFormTest {

    @Test
    void createFormExposesBufferInputs() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("name=\"bufferBeforeMinutes\""))
                .body(containsString("name=\"bufferAfterMinutes\""));
    }

    @Test
    void createPersistsSeparateBuffers() {
        var slug = "buffers-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Buffered Call")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("bufferBeforeMinutes", "10")
                .formParam("bufferAfterMinutes", "15")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(10, t.bufferBeforeMinutes);
        assertEquals(15, t.bufferAfterMinutes);
    }

    @Test
    void blankSlugIsGeneratedFromName() {
        var name = "Discovery Chat " + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", name)
                .formParam("slug", "") // blank -> server generates
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, Slugs.slugify(name));
        org.junit.jupiter.api.Assertions.assertNotNull(t);
    }

    @Test
    void duplicateGeneratedSlugGetsSuffix() {
        var name = "Repeat Topic " + System.nanoTime();
        String base = Slugs.slugify(name);
        for (var i = 0; i < 2; i++) {
            given().cookie("quarkus-credential", FormAuth.login())
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("name", name)
                    .formParam("slug", "")
                    .formParam("durationMinutes", "30")
                    .formParam("minNoticeMinutes", "0")
                    .formParam("horizonDays", "60")
                    .formParam("locationType", "GOOGLE_MEET")
                    .formParam("locationDetail", "")
                    .formParam("slotIntervalMinutes", "")
                    .when()
                    .post("/me/meeting-types")
                    .then()
                    .statusCode(200);
        }
        org.junit.jupiter.api.Assertions.assertNotNull(MeetingType.findBySlug(1L, base));
        org.junit.jupiter.api.Assertions.assertNotNull(MeetingType.findBySlug(1L, base + "-2"));
    }

    @Test
    void createFormHasDynamicMinNoticeDefault() {
        // Static fallback = default duration 30 * 4 = 120; marker = script hook for live recompute.
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("name=\"minNoticeMinutes\" value=\"120\""))
                .body(containsString("data-min-notice-auto"));
    }

    @Test
    void slugInputHasLiveFillScript() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                .body(containsString("data-slug-autofill")); // marker the JS hooks onto
    }

    @Test
    void createFormRendersWorkplanGrid() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/meeting-types")
                .then()
                .statusCode(200)
                // grid scope + the markers workplan.js binds to
                .body(containsString("data-workplan"))
                .body(containsString("data-day=\"MONDAY\""))
                .body(containsString("data-day=\"SUNDAY\""))
                .body(containsString("data-frame-template"))
                .body(containsString("data-add-frame=\"MONDAY\""))
                .body(containsString("data-copy-all=\"MONDAY\""))
                .body(containsString("data-copy-weekdays=\"MONDAY\""))
                .body(containsString("data-clear-day=\"SUNDAY\""))
                .body(containsString("/workplan.js"))
                // frame inputs replace the old ruleDay/ruleStart/ruleEnd trio
                .body(containsString("name=\"frameDay\""))
                .body(containsString("name=\"frameStart\""))
                .body(containsString("name=\"frameEnd\""))
                .body(not(containsString("name=\"ruleDay\"")))
                // seeded row's hidden frameDay actually carries its OWN day, not a copy-pasted constant
                .body(containsString("name=\"frameDay\" value=\"SUNDAY\""))
                // seeded blank frame renders an empty value, never the literal text "null"
                .body(containsString("name=\"frameStart\" value=\"\""))
                .body(containsString("name=\"overrideDate\"")); // date-override block untouched
    }

    @Test
    void createPersistsPerTypeWorkingHoursFromFrames() {
        var slug = "wh-create-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "With Hours")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                // one filled frame + one blank frame (must be skipped)
                .formParam("frameDay", "MONDAY", "TUESDAY")
                .formParam("frameStart", "09:00", "")
                .formParam("frameEnd", "17:00", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1", t.id)); // blank Tuesday skipped
    }

    @Test
    void createPersistsSeveralFramesOnOneDay() {
        var slug = "wh-multi-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Split Day")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                // what "+ Frame" produces: two Monday frames, plus one inverted frame to drop
                .formParam("frameDay", "MONDAY", "MONDAY", "FRIDAY")
                .formParam("frameStart", "09:00", "13:00", "17:00")
                .formParam("frameEnd", "12:00", "17:00", "09:00")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(
                2,
                AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY),
                "both Monday frames persist");
        assertEquals(
                0,
                AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.FRIDAY),
                "inverted frame dropped, not 500");
    }

    @Test
    void noJsSubmitOfTheSeededGridCreatesTheTypedRules() {
        var slug = "wh-nojs-" + System.nanoTime();
        // Exactly what the browser posts with JS disabled: the seven server-seeded rows,
        // two of them filled in by hand, the rest left blank.
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "No JS")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "PHONE")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("frameDay", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
                .formParam("frameStart", "09:00", "", "10:00", "", "", "", "")
                .formParam("frameEnd", "17:00", "", "16:00", "", "", "", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(2, AvailabilityRule.count("meetingTypeId = ?1", t.id));
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY));
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.WEDNESDAY));
    }

    @Test
    void createPersistsPerTypeDateOverrideWithWindow() {
        var slug = "ov-create-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "With Override")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("overrideDate", "2026-12-24")
                .formParam("windowStart", "09:00")
                .formParam("windowEnd", "11:00")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        site.asm0dey.calit.domain.DateOverride o = site.asm0dey.calit.domain.DateOverride.find(
                        "meetingTypeId = ?1", t.id)
                .firstResult();
        assertNotNull(o);
        assertEquals(1, site.asm0dey.calit.domain.DateOverrideWindow.count("dateOverrideId = ?1", o.id));
    }

    @Test
    void createWithGarbageOverrideDateStillCreatesTheType() {
        var slug = "ov-garbage-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Garbage Date")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("overrideDate", "not-a-date") // crafted/garbage input, must not 500
                .formParam("windowStart", "09:00")
                .formParam("windowEnd", "11:00")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t); // the type itself is still created
        assertEquals(
                0,
                site.asm0dey.calit.domain.DateOverride.count("meetingTypeId = ?1", t.id),
                "unparseable date skipped, not persisted");
    }

    @Test
    void createWithGarbageWindowTimeSkipsThatWindowButKeepsTheOverride() {
        var slug = "ov-garbage-window-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Garbage Window")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .formParam("overrideDate", "2026-12-24")
                // first window is garbage (must not 500); second is a valid window that must still persist
                .formParam("windowStart", "not-a-time", "13:00")
                .formParam("windowEnd", "11:00", "14:00")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        site.asm0dey.calit.domain.DateOverride o = site.asm0dey.calit.domain.DateOverride.find(
                        "meetingTypeId = ?1", t.id)
                .firstResult();
        assertNotNull(o, "valid date still persists the override");
        assertEquals(
                1,
                site.asm0dey.calit.domain.DateOverrideWindow.count("dateOverrideId = ?1", o.id),
                "garbage window skipped, valid window kept");
    }

    @Test
    void createWithoutWorkingHoursMakesNoRules() {
        var slug = "nowh-create-" + System.nanoTime();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "No Hours")
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "60")
                .formParam("locationType", "GOOGLE_MEET")
                .formParam("locationDetail", "")
                .formParam("slotIntervalMinutes", "")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        assertNotNull(t);
        assertEquals(0, site.asm0dey.calit.domain.AvailabilityRule.count("meetingTypeId = ?1", t.id));
    }
}
