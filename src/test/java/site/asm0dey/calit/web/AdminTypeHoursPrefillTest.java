package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.MeetingType;

/**
 * Issue #127: a type's weekly grid is its whole week, so the editor must not start out empty and
 * silently close every day. With no per-type rules the grid renders PREFILLED from the owner's
 * global hours; once the type has rules of its own, only those show.
 */
@QuarkusTest
class AdminTypeHoursPrefillTest {

    private static String seedGlobalHoursAndType(String cred, String slug) {
        // Global week: Monday 07:15-11:45 only (times unique enough to spot in the rendered grid).
        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "MONDAY")
                .formParam("frameStart", "07:15")
                .formParam("frameEnd", "11:45")
                .when()
                .post("/me/availability/bulk")
                .then()
                .statusCode(200);

        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("name", slug)
                .formParam("slug", slug)
                .formParam("durationMinutes", "30")
                .formParam("minNoticeMinutes", "0")
                .formParam("horizonDays", "30")
                .formParam("locationType", "PHONE")
                .when()
                .post("/me/meeting-types")
                .then()
                .statusCode(200);
        MeetingType t = MeetingType.find("slug = ?1", slug).firstResult();
        return "/me/meeting-types/" + t.id;
    }

    @Test
    void gridOfATypeWithoutOwnHoursIsPrefilledFromTheGlobalWeek() {
        String cred = FormAuth.login();
        var detail = seedGlobalHoursAndType(cred, "prefill-type");

        given().cookie("quarkus-credential", cred)
                .when()
                .get(detail)
                .then()
                .statusCode(200)
                .body(containsString("value=\"07:15\""))
                .body(containsString("value=\"11:45\""));
    }

    @Test
    void gridShowsOnlyTheTypesOwnHoursOnceItHasSome() {
        String cred = FormAuth.login();
        var detail = seedGlobalHoursAndType(cred, "own-hours-type");

        given().cookie("quarkus-credential", cred)
                .contentType("application/x-www-form-urlencoded")
                .formParam("frameDay", "TUESDAY")
                .formParam("frameStart", "14:05")
                .formParam("frameEnd", "16:35")
                .when()
                .post(detail + "/availability/bulk")
                .then()
                .statusCode(200);

        given().cookie("quarkus-credential", cred)
                .when()
                .get(detail)
                .then()
                .statusCode(200)
                .body(containsString("value=\"14:05\""))
                .body(not(containsString("value=\"07:15\"")), not(containsString("value=\"11:45\"")));
    }

    /** The per-day "Remove availability" button drives workplan.js's data-clear-day handler. */
    @Test
    void everyDayRowOffersRemoveAvailability() {
        String cred = FormAuth.login();
        var detail = seedGlobalHoursAndType(cred, "clear-day-type");

        given().cookie("quarkus-credential", cred)
                .when()
                .get(detail)
                .then()
                .statusCode(200)
                .body(containsString("data-clear-day=\"MONDAY\""))
                .body(containsString("data-clear-day=\"SUNDAY\""));

        given().cookie("quarkus-credential", cred)
                .when()
                .get("/me/availability")
                .then()
                .statusCode(200)
                .body(containsString("data-clear-day=\"SUNDAY\""));
    }
}
