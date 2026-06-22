package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.DateOverride;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class AdminDateOverridesTest {

    @Transactional
    void seedOverride() {
        // A global day-off override (no windows) for a fixed date.
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = null;
        o.overrideDate = java.time.LocalDate.of(2026, 12, 25); // Christmas — blocked
        o.windows = new java.util.ArrayList<>();        // empty = day off
        o.persist();
    }

    @Test
    void pageRendersExistingOverridesAndCreateForm() {
        seedOverride();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/me/date-overrides")
            .then()
                .statusCode(200)
                .body(containsString("2026-12-25"))           // existing override listed
                .body(containsString("day off"))               // empty-windows label
                .body(containsString("name=\"date\""))         // create form date input
                .body(containsString("name=\"windowStart\"")) // window start input
                .body(containsString("name=\"windowEnd\""))   // window end input
                .body(containsString("name=\"meetingTypeId\"")); // type selector (global option)
    }

    @Test
    void createOverrideWithWindowsViaForm() {
        long before = DateOverride.count();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "2026-07-01")
            .formParam("meetingTypeId", "")          // empty = global
            .formParam("windowStart", "10:00")        // one window 10:00–14:00
            .formParam("windowEnd", "14:00")
            .when().post("/me/date-overrides")
            .then()
                .statusCode(200)
                .body(containsString("2026-07-01"))
                .body(containsString("10:00"));

        org.junit.jupiter.api.Assertions.assertEquals(before + 1, DateOverride.count());
    }

    @Test
    void createDayOffOverrideWithNoWindows() {
        // No windowStart/windowEnd at all → an override with zero windows (day off / blocked).
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "2026-08-15")
            .formParam("meetingTypeId", "")
            .when().post("/me/date-overrides")
            .then()
                .statusCode(200)
                .body(containsString("2026-08-15"))
                .body(containsString("day off"));
    }

    @Test
    void dateOverridesPageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/date-overrides").then().statusCode(302);
    }
}
