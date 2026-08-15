package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

/**
 * /me pages have no #tz-picker, so TZ_SCRIPT used to bail at "if (!picker) return" and leave the
 * raw ISO instant on screen. The script must now format without a picker, using the owner's
 * STORED timezone (not the browser-detected one — a travelling host must not silently read their
 * bookings in the trip's zone).
 *
 * <p>RestAssured cannot execute JS, so these assert on the served HTML and the script text.</p>
 */
@QuarkusTest
class AdminTimeRenderingTest {

    /** Saves a known timezone so the assertion below is deterministic. */
    private void saveTimezone(String zone) {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", zone)
                .formParam("locale", "en")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void dashboardCarriesTheOwnersStoredTimezone() {
        saveTimezone("Europe/Amsterdam");

        given().when().get("/me").then().statusCode(200).body(containsString("data-tz=\"Europe/Amsterdam\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void pendingCarriesTheOwnersStoredTimezone() {
        saveTimezone("Asia/Tokyo");

        given().when().get("/me/pending").then().statusCode(200).body(containsString("data-tz=\"Asia/Tokyo\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void scriptNoLongerBailsWhenThereIsNoPicker() {
        given().when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("CALIT_TZ_REFORMAT"))
                // the early return is gone
                .body(not(containsString("if (!picker) { return; }")))
                // and the no-picker path reads the server-supplied zone
                .body(containsString("document.body.dataset.tz"))
                // pin the fallback ORDER, not just that both operands appear somewhere: RestAssured
                // can't execute the script, so a bare "dataset.tz" check would still pass if the
                // ternary were silently inverted to "(detected || document.body.dataset.tz)" — which
                // would reintroduce the original bug (a travelling host reading their bookings in
                // the trip's timezone instead of their configured one).
                .body(containsString("picker ? picker.value : (document.body.dataset.tz || detected)"));
    }
}
