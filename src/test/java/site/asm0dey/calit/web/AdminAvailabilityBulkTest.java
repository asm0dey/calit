package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.AvailabilityRule;
import site.asm0dey.calit.domain.MeetingType;

import java.time.DayOfWeek;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AdminAvailabilityBulkTest {

    private static long globalCount(DayOfWeek day) {
        return AvailabilityRule.count("meetingTypeId is null and dayOfWeek = ?1", day);
    }

    @Test
    void bulkSaveReplacesAllGlobalRules() {
        String cred = FormAuth.login();

        // Seed a stale global rule (legacy single-add endpoint) that the bulk save must wipe.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "SUNDAY")
                .formParam("startTime", "08:00").formParam("endTime", "09:00")
                .formParam("meetingTypeId", "")
                .when().post("/me/availability").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.SUNDAY));

        // Bulk replace: two Monday frames, nothing else.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY", "MONDAY")
                .formParam("frameStart", "09:00", "13:00")
                .formParam("frameEnd", "12:00", "17:00")
                .when().post("/me/availability/bulk").then().statusCode(200);

        assertEquals(0, globalCount(DayOfWeek.SUNDAY), "stale rule should be wiped");
        assertEquals(2, globalCount(DayOfWeek.MONDAY), "two new Monday frames");
    }

    @Test
    void bulkSaveSkipsBlankAndInvertedFrames() {
        String cred = FormAuth.login();
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "TUESDAY", "TUESDAY", "TUESDAY")
                .formParam("frameStart", "09:00", "", "17:00") // blank start; inverted end<=start
                .formParam("frameEnd", "12:00", "12:00", "09:00")
                .when().post("/me/availability/bulk").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.TUESDAY), "only the valid frame persists");
    }

    @Test
    void bulkSavePerTypeReplacesOnlyThatTypesRules() {
        String cred = FormAuth.login();

        // Create a meeting type to scope per-type rules to.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Bulk Type").formParam("slug", "bulk-type")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0").formParam("horizonDays", "30")
                .formParam("locationType", "PHONE")
                .when().post("/me/meeting-types").then().statusCode(200);
        MeetingType t = MeetingType.find("slug = ?1", "bulk-type").firstResult();

        // Seed a stale per-type rule via the legacy endpoint, then bulk-replace.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "FRIDAY")
                .formParam("startTime", "08:00").formParam("endTime", "09:00")
                .when().post("/me/meeting-types/" + t.id + "/availability").then().statusCode(200);

        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY")
                .formParam("frameStart", "10:00").formParam("frameEnd", "14:00")
                .when().post("/me/meeting-types/" + t.id + "/availability/bulk").then().statusCode(200);

        assertEquals(0, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.FRIDAY));
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY));
    }

    @Test
    void bulkSavesAreScopeIsolated() {
        String cred = FormAuth.login();

        // A global rule and a per-type rule that must each survive the OTHER scope's bulk save.
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Iso Type").formParam("slug", "iso-type")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0").formParam("horizonDays", "30")
                .formParam("locationType", "PHONE")
                .when().post("/me/meeting-types").then().statusCode(200);
        MeetingType t = MeetingType.find("slug = ?1", "iso-type").firstResult();

        // Seed: global SUNDAY rule + per-type FRIDAY rule.
        given().cookie("quarkus-credential", cred).contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "SUNDAY").formParam("startTime", "08:00").formParam("endTime", "09:00")
                .formParam("meetingTypeId", "")
                .when().post("/me/availability").then().statusCode(200);
        given().cookie("quarkus-credential", cred).contentType("application/x-www-form-urlencoded")
                .formParam("dayOfWeek", "FRIDAY").formParam("startTime", "08:00").formParam("endTime", "09:00")
                .when().post("/me/meeting-types/" + t.id + "/availability").then().statusCode(200);

        // Per-type bulk save must NOT touch the global SUNDAY rule.
        given().cookie("quarkus-credential", cred).contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY").formParam("frameStart", "10:00").formParam("frameEnd", "14:00")
                .when().post("/me/meeting-types/" + t.id + "/availability/bulk").then().statusCode(200);
        assertEquals(1, globalCount(DayOfWeek.SUNDAY), "global rule survives per-type bulk save");

        // Global bulk save must NOT touch the per-type rules (MONDAY frame from above survives).
        given().cookie("quarkus-credential", cred).contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "WEDNESDAY").formParam("frameStart", "09:00").formParam("frameEnd", "17:00")
                .when().post("/me/availability/bulk").then().statusCode(200);
        assertEquals(1, AvailabilityRule.count("meetingTypeId = ?1 and dayOfWeek = ?2", t.id, DayOfWeek.MONDAY),
                "per-type rule survives global bulk save");
        assertEquals(0, globalCount(DayOfWeek.SUNDAY), "global bulk save did replace the global scope");
        assertEquals(1, globalCount(DayOfWeek.WEDNESDAY), "global bulk save wrote the new global frame");
    }

    @Test
    void availabilityPageRendersSevenDayGridWithCopyButtons() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when().get("/me/availability")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("data-workplan"))
                .body(org.hamcrest.Matchers.containsString("data-day=\"MONDAY\""))
                .body(org.hamcrest.Matchers.containsString("data-day=\"SUNDAY\""))
                .body(org.hamcrest.Matchers.containsString("Copy to all days"))
                .body(org.hamcrest.Matchers.containsString("Copy to weekdays"))
                .body(org.hamcrest.Matchers.containsString("/workplan.js"));
    }

    @Test
    void typeDetailRendersSevenDayGrid() {
        String cred = FormAuth.login();
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", "Grid Type").formParam("slug", "grid-type")
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0").formParam("horizonDays", "30")
                .formParam("locationType", "PHONE")
                .when().post("/me/meeting-types").then().statusCode(200);
        site.asm0dey.calit.domain.MeetingType t = site.asm0dey.calit.domain.MeetingType.find("slug = ?1", "grid-type").firstResult();

        given().cookie("quarkus-credential", cred)
                .when().get("/me/meeting-types/" + t.id)
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.containsString("data-workplan"))
                .body(org.hamcrest.Matchers.containsString("data-day=\"MONDAY\""))
                .body(org.hamcrest.Matchers.containsString("/me/meeting-types/" + t.id + "/availability/bulk"))
                .body(org.hamcrest.Matchers.containsString("Copy to weekdays"));
    }
}
