package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.DateOverride;

@QuarkusTest
class AdminDateOverridesTest {

    @Transactional
    void seedOverride() {
        // A global day-off override (no windows) for a fixed date.
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = null;
        o.overrideDate = java.time.LocalDate.of(2026, 12, 25); // Christmas — blocked
        o.windows = new java.util.ArrayList<>(); // empty = day off
        o.persist();
    }

    @Test
    void pageRendersExistingOverridesAndCreateForm() {
        seedOverride();
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/date-overrides")
                .then()
                .statusCode(200)
                .body(containsString("2026-12-25")) // existing override listed
                .body(containsString("day off")) // empty-windows label
                .body(containsString("name=\"date\"")) // create form date input
                .body(containsString("name=\"windowStart\"")) // window start input
                .body(containsString("name=\"windowEnd\"")) // window end input
                .body(containsString("name=\"meetingTypeId\"")); // type selector (global option)
    }

    @Test
    void createOverrideWithWindowsViaForm() {
        long before = DateOverride.count();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("date", "2026-07-01")
                .formParam("meetingTypeId", "") // empty = global
                .formParam("windowStart", "10:00") // one window 10:00–14:00
                .formParam("windowEnd", "14:00")
                .when()
                .post("/me/date-overrides")
                .then()
                .statusCode(200)
                .body(containsString("2026-07-01"))
                .body(containsString("10:00"));

        org.junit.jupiter.api.Assertions.assertEquals(before + 1, DateOverride.count());
    }

    @Test
    void createDayOffOverrideWithNoWindows() {
        // No windowStart/windowEnd at all → an override with zero windows (day off / blocked).
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("date", "2026-08-15")
                .formParam("meetingTypeId", "")
                .when()
                .post("/me/date-overrides")
                .then()
                .statusCode(200)
                .body(containsString("2026-08-15"))
                .body(containsString("day off"));
    }

    @Test
    void createOverrideWithGarbageDateReturns400AndPersistsNothing() {
        // The global MalformedDateTimeMapper (registered for DateTimeParseException) already turns
        // an unguarded LocalDate.parse(date) into a clean 400 here — verified empirically, not
        // assumed. This test pins that behaviour.
        long before = DateOverride.count();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("date", "not-a-date")
                .formParam("meetingTypeId", "")
                .when()
                .post("/me/date-overrides")
                .then()
                .statusCode(400);

        org.junit.jupiter.api.Assertions.assertEquals(before, DateOverride.count());
    }

    @Test
    void createOverrideWithGarbageMeetingTypeIdReturns400AndPersistsNothing() {
        // Long.valueOf(meetingTypeId) had no ExceptionMapper covering NumberFormatException, so a
        // non-numeric id 500ed until AdminResource.createOverride was given an explicit guard that
        // throws BadRequestException (JAX-RS maps that to 400 with no extra mapper needed).
        long before = DateOverride.count();
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("date", "2026-07-01")
                .formParam("meetingTypeId", "not-a-number")
                .when()
                .post("/me/date-overrides")
                .then()
                .statusCode(400);

        org.junit.jupiter.api.Assertions.assertEquals(before, DateOverride.count());
    }

    @Test
    void dateOverridesPageRequiresAuth() {
        given().redirects()
                .follow(false)
                .when()
                .get("/me/date-overrides")
                .then()
                .statusCode(302);
    }

    private static final String PAST_MARKER = "id=\"past-overrides\"";

    /** Seeds one global day-off override on the given date for owner 1. */
    @Transactional
    void seedOverrideOn(LocalDate date) {
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = null;
        o.overrideDate = date;
        o.windows = new java.util.ArrayList<>();
        o.persist();
    }

    private String pageBody() {
        return given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me/date-overrides")
                .then()
                .statusCode(200)
                .extract()
                .body()
                .asString();
    }

    @Test
    void pastOverridesRenderInsideTheCollapsedSectionAndUpcomingOnesAboveIt() {
        // Owner 1 has no owner_settings row in tests, so the page's "today" is UTC today.
        // +/-30 days keeps both sides of the split unambiguous under any timezone.
        var future = LocalDate.now(ZoneOffset.UTC).plusDays(30);
        var history = LocalDate.now(ZoneOffset.UTC).minusDays(30);
        seedOverrideOn(future);
        seedOverrideOn(history);

        var body = pageBody();
        var marker = body.indexOf(PAST_MARKER);
        assertTrue(marker >= 0, "expected the past-overrides collapse to be rendered");

        var beforeCollapse = body.substring(0, marker);
        var insideCollapse = body.substring(marker);

        assertTrue(beforeCollapse.contains(future.toString()), "upcoming override must render above the collapse");
        assertFalse(beforeCollapse.contains(history.toString()), "past override must not render above the collapse");
        assertTrue(insideCollapse.contains(history.toString()), "past override must render inside the collapse");
    }

    @Test
    void todaysOverrideCountsAsUpcoming() {
        // An override for today still governs today's bookable slots, so it belongs above the fold.
        var today = LocalDate.now(ZoneOffset.UTC);
        seedOverrideOn(today);

        var body = pageBody();
        var marker = body.indexOf(PAST_MARKER);
        var beforeCollapse = marker >= 0 ? body.substring(0, marker) : body;

        assertTrue(beforeCollapse.contains(today.toString()), "today's override must be treated as upcoming");
    }

    @Test
    void noCollapseIsRenderedWhenThereAreNoPastOverrides() {
        seedOverrideOn(LocalDate.now(ZoneOffset.UTC).plusDays(30));

        assertFalse(pageBody().contains(PAST_MARKER), "an owner with no past overrides gets no empty collapse");
    }
}
