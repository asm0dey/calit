package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.*;

@QuarkusTest
class AdminMeetingTypeFormTest {
    @Test
    void createFormExposesBufferInputs() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        assertThat(t.bufferBeforeMinutes).isEqualTo(10);
        assertThat(t.bufferAfterMinutes).isEqualTo(15);
    }

    @Test
    void blankSlugIsGeneratedFromName() {
        var name = "Discovery Chat " + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", name)
            // blank -> server generates
            .formParam(
                    // blank -> server generates
            "slug",
                    "")
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
        assertThat(t).isNotNull();
    }

    @Test
    void duplicateGeneratedSlugGetsSuffix() {
        var name = "Repeat Topic " + System.nanoTime();
        String base = Slugs.slugify(name);
        for (var i = 0; i < 2; i++) {
            given()
                .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(MeetingType.findBySlug(1L, base)).isNotNull();
        assertThat(MeetingType.findBySlug(1L, base + "-2")).isNotNull();
    }

    @Test
    void createFormHasDynamicMinNoticeDefault() {
        // Static fallback = default duration 30 * 4 = 120; marker = script hook for live recompute.
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types")
            .then()
            .statusCode(200)
            .body(containsString("name=\"minNoticeMinutes\" value=\"120\""))
            .body(containsString("data-min-notice-auto"));
    }

    @Test
    void slugInputHasLiveFillScript() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types")
            .then()
            .statusCode(200)
            // marker the JS hooks onto
            .body(containsString("data-slug-autofill"));
    }

    @Test
    void createFormRendersWorkplanGrid() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
            // date-override block untouched
            .body(containsString("name=\"overrideDate\""));
    }

    @Test
    void createPersistsPerTypeWorkingHoursFromFrames() {
        var slug = "wh-create-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        // blank Tuesday skipped
        assertThat(AvailabilityRule.count("meetingTypeId = ?1", t.id)).isOne();
    }

    @Test
    void createPersistsSeveralFramesOnOneDay() {
        var slug = "wh-multi-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        assertThat(AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY))
            .as("both Monday frames persist")
            .isEqualTo(2);
        assertThat(AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.FRIDAY))
            .as("inverted frame dropped, not 500")
            .isZero();
    }

    @Test
    void noJsSubmitOfTheSeededGridCreatesTheTypedRules() {
        var slug = "wh-nojs-" + System.nanoTime();
        // Exactly what the browser posts with JS disabled: the seven server-seeded rows,
        // two of them filled in by hand, the rest left blank.
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        assertThat(AvailabilityRule.count("meetingTypeId = ?1", t.id)).isEqualTo(2);
        assertThat(AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY)).isOne();
        assertThat(AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.WEDNESDAY)).isOne();
    }

    @Test
    void createPersistsPerTypeDateOverrideWithWindow() {
        var slug = "ov-create-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        DateOverride o = DateOverride.find("meetingTypeId = ?1", t.id).firstResult();
        assertThat(o).isNotNull();
        assertThat(DateOverrideWindow.count("dateOverrideId = ?1", o.id)).isOne();
    }

    @Test
    void createWithGarbageOverrideDateStillCreatesTheType() {
        var slug = "ov-garbage-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Garbage Date")
            .formParam("slug", slug)
            .formParam("durationMinutes", "30")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("locationDetail", "")
            .formParam("slotIntervalMinutes", "")
            // crafted/garbage input, must not 500
            .formParam(
                    // crafted/garbage input, must not 500
            "overrideDate",
                    "not-a-date")
            .formParam("windowStart", "09:00")
            .formParam("windowEnd", "11:00")
            .when()
            .post("/me/meeting-types")
            .then()
            .statusCode(200);

        MeetingType t = MeetingType.findBySlug(1L, slug);
        // the type itself is still created
        assertThat(t).isNotNull();
        assertThat(DateOverride.count("meetingTypeId = ?1", t.id)).as("unparseable date skipped, not persisted").isZero();
    }

    @Test
    void createWithGarbageWindowTimeSkipsThatWindowButKeepsTheOverride() {
        var slug = "ov-garbage-window-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        DateOverride o = DateOverride.find("meetingTypeId = ?1", t.id).firstResult();
        assertThat(o).as("valid date still persists the override").isNotNull();
        assertThat(DateOverrideWindow.count("dateOverrideId = ?1", o.id))
            .as("garbage window skipped, valid window kept")
            .isOne();
    }

    @Test
    void createWithoutWorkingHoursMakesNoRules() {
        var slug = "nowh-create-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(t).isNotNull();
        assertThat(AvailabilityRule.count("meetingTypeId = ?1", t.id)).isZero();
    }

    @Inject
    EntityManager em;

    @Transactional
    MeetingType reload(Long id) {
        // the edit commits in its own request tx; drop the test thread's stale first-level cache
        em.clear();
        return MeetingType.findById(id);
    }

    private String slugOfNewType;

    private Long newType() {
        var slug = slugOfNewType = "ms-" + System.nanoTime();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Multi")
            .formParam("slug", slug)
            .formParam("durationMinutes", "30")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("slotIntervalMinutes", "")
            .when()
            .post("/me/meeting-types")
            .then()
            .statusCode(200);
        return MeetingType.findBySlug(1L, slug).id;
    }

    private io.restassured.response.Response edit(
            Long id,
            String mode,
            String min,
            String max,
            String exact,
            boolean sameDay
    ) {
        var req = given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Multi")
            .formParam("slug", slugOfNewType)
            .formParam("durationMinutes", "30")
            .formParam("minNoticeMinutes", "0")
            .formParam("horizonDays", "60")
            .formParam("locationType", "GOOGLE_MEET")
            .formParam("slotIntervalMinutes", "")
            .formParam("multiSlotMode", mode)
            .formParam("multiSlotMin", min)
            .formParam("multiSlotMax", max)
            .formParam("multiSlotExact", exact);
        if (sameDay) {
            req = req.formParam("multiSlotSameDay", "on");
        }
        return req.when().post("/me/meeting-types/" + id + "/edit");
    }

    @Test
    void multiSlotRangeIsStored() {
        var id = newType();
        edit(id, "range", "2", "4", "", true).then().statusCode(200);
        MeetingType t = reload(id);
        assertThat(t.multiSlotMin).isEqualTo(2);
        assertThat(t.multiSlotMax).isEqualTo(4);
        assertThat(t.multiSlotSameDay).isTrue();
    }

    @Test
    void multiSlotExactStoresMinEqualsMax() {
        var id = newType();
        edit(id, "exact", "", "", "3", false).then().statusCode(200);
        MeetingType t = reload(id);
        assertThat(t.multiSlotMin).isEqualTo(3);
        assertThat(t.multiSlotMax).isEqualTo(3);
    }

    @Test
    void multiSlotOffClearsBoth() {
        var id = newType();
        edit(id, "range", "2", "4", "", true).then().statusCode(200);
        edit(id, "off", "2", "4", "", true).then().statusCode(200);
        MeetingType t = reload(id);
        assertThat(t.multiSlotMin).isNull();
        assertThat(t.multiSlotMax).isNull();
        assertThat(t.multiSlotSameDay).isFalse();
    }

    @Test
    void multiSlotOutOfRangeIsRefused() {
        var id = newType();
        edit(id, "range", "0", "6", "", false).then().statusCode(200).body(containsString("alert-error"));
        edit(id, "range", "4", "2", "", false).then().statusCode(200).body(containsString("alert-error"));
        edit(id, "exact", "", "", "1", false).then().statusCode(200).body(containsString("alert-error"));
        MeetingType t = reload(id);
        assertThat(t.multiSlotMin).isNull();
        assertThat(t.multiSlotMax).isNull();
    }

    @Test
    void editPageRendersToggleNotCheckbox() {
        var id = newType();
        edit(id, "range", "2", "4", "", true).then().statusCode(200);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .body(containsString("name=\"multiSlotSameDay\""))
            .body(containsString("class=\"toggle"));
    }

    @Test
    void rangeOneToOneReloadsAsRangeAndResaves() {
        var id = newType();
        edit(id, "range", "1", "1", "", false).then().statusCode(200);
        var html = given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + id)
            .then()
            .statusCode(200)
            .extract()
            .asString();
        assertThat(html).containsPattern("value=\"range\"[^>]*checked").doesNotContainPattern(
                "value=\"exact\"[^>]*checked"
        );
        edit(id, "range", "1", "1", "", false).then().statusCode(200).body(not(containsString("alert-error")));
        MeetingType t = reload(id);
        assertThat(t.multiSlotMin).isEqualTo(1);
        assertThat(t.multiSlotMax).isEqualTo(1);
    }
}
