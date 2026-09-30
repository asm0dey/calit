package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.DateOverride;
import site.asm0dey.calit.domain.DateOverrideWindow;

@QuarkusTest
class AdminDateOverridesTest {
    @Transactional
    void seedOverride() {
        // A global day-off override (no windows) for a fixed date.
        DateOverride o = new DateOverride();
        o.ownerId = 1L;
        o.meetingTypeId = null;
        // Christmas — blocked
        o.overrideDate = LocalDate.of(2026, 12, 25);
        // empty = day off
        o.windows = new java.util.ArrayList<>();
        o.persist();
    }

    @Test
    void pageRendersExistingOverridesAndCreateForm() {
        seedOverride();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/date-overrides")
            .then()
            .statusCode(200)
            // existing override listed
            .body(containsString("2026-12-25"))
            // empty-windows label
            .body(containsString("day off"))
            // create form date input
            .body(containsString("name=\"date\""))
            // window start input
            .body(containsString("name=\"windowStart\""))
            // window end input
            .body(containsString("name=\"windowEnd\""))
            // type selector (global option)
            .body(containsString("name=\"meetingTypeId\""));
    }

    @Test
    void createOverrideWithWindowsViaForm() {
        long before = DateOverride.count();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "2026-07-01")
            // empty = global
            .formParam(
                    // empty = global
            "meetingTypeId",
                    "")
            // one window 10:00–14:00
            .formParam(
                    // one window 10:00–14:00
            "windowStart",
                    "10:00")
            .formParam("windowEnd", "14:00")
            .when()
            .post("/me/date-overrides")
            .then()
            .statusCode(200)
            .body(containsString("2026-07-01"))
            .body(containsString("10:00"));

        assertThat(DateOverride.count()).isEqualTo(before + 1);
    }

    @Transactional
    List<DateOverrideWindow> windowsOn(LocalDate date) {
        DateOverride o = DateOverride.find("ownerId = ?1 and overrideDate = ?2", 1L, date).firstResult();
        return DateOverrideWindow.list("dateOverrideId = ?1 order by startTime", o.id);
    }

    @Test
    void createOverrideDropsInvertedWindowsAndKeepsAtMostThree() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "2026-07-02")
            .formParam("meetingTypeId", "")
            .formParam("windowStart", "12:00", "08:00", "09:00", "10:00", "11:00")
            .formParam("windowEnd", "11:00", "08:30", "09:30", "10:30", "11:30")
            .when()
            .post("/me/date-overrides")
            .then()
            .statusCode(200);
        // the inverted 12:00-11:00 is dropped and only the first three valid windows are kept
        assertThat(windowsOn(LocalDate.of(2026, 7, 2))
            .stream()
            .map(w -> w.startTime + "-" + w.endTime)
            .toList())
            .containsExactly("08:00-08:30", "09:00-09:30", "10:00-10:30");
    }

    @Test
    void createDayOffOverrideWithNoWindows() {
        // No windowStart/windowEnd at all → an override with zero windows (day off / blocked).
        given()
            .cookie("quarkus-credential", FormAuth.login())
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
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "not-a-date")
            .formParam("meetingTypeId", "")
            .when()
            .post("/me/date-overrides")
            .then()
            .statusCode(400);

        assertThat(DateOverride.count()).isEqualTo(before);
    }

    @Test
    void createOverrideWithGarbageMeetingTypeIdReturns400AndPersistsNothing() {
        // Long.valueOf(meetingTypeId) had no ExceptionMapper covering NumberFormatException, so a
        // non-numeric id 500ed until AdminResource.createOverride was given an explicit guard that
        // throws BadRequestException (JAX-RS maps that to 400 with no extra mapper needed).
        long before = DateOverride.count();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("date", "2026-07-01")
            .formParam("meetingTypeId", "not-a-number")
            .when()
            .post("/me/date-overrides")
            .then()
            .statusCode(400);

        assertThat(DateOverride.count()).isEqualTo(before);
    }

    @Test
    void dateOverridesPageRequiresAuth() {
        given().redirects().follow(false).when().get("/me/date-overrides").then().statusCode(302);
    }

    private static final String PAST_MARKER = "id=\"past-overrides\"";

    /**
     * Seeds one global day-off override on the given date for owner 1.
     */
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
        return given()
            .cookie("quarkus-credential", FormAuth.login())
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
        assertThat(marker).as("expected the past-overrides collapse to be rendered").isGreaterThanOrEqualTo(0);

        var beforeCollapse = body.substring(0, marker);
        var insideCollapse = body.substring(marker);

        assertThat(beforeCollapse).as("upcoming override must render above the collapse").contains(future.toString());
        assertThat(beforeCollapse).as("past override must not render above the collapse").doesNotContain(history.toString()
        );
        assertThat(insideCollapse).as("past override must render inside the collapse").contains(history.toString());
        assertThat(body).as("collapse summary must show the correct past count").contains("Past overrides (1)");
    }

    @Test
    void todaysOverrideCountsAsUpcoming() {
        // An override for today still governs today's bookable slots, so it belongs above the fold.
        var today = LocalDate.now(ZoneOffset.UTC);
        seedOverrideOn(today);

        var body = pageBody();
        var marker = body.indexOf(PAST_MARKER);
        var beforeCollapse = marker >= 0 ? body.substring(0, marker) : body;

        assertThat(beforeCollapse).as("today's override must be treated as upcoming").contains(today.toString());
    }

    @Test
    void noCollapseIsRenderedWhenThereAreNoPastOverrides() {
        seedOverrideOn(LocalDate.now(ZoneOffset.UTC).plusDays(30));

        assertThat(pageBody()).as("an owner with no past overrides gets no empty collapse").doesNotContain(PAST_MARKER);
    }
}
