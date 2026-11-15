package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Task 9c: verifies that the auth / bootstrap pages render in the correct language
 * based on the calit_lang cookie, and that the base layout emits the matching lang attribute.
 *
 * Reachability notes:
 *  - /login        : always reachable (anonymous)
 *  - /forgot-password : always reachable (anonymous)
 *  - /signup       : 404 unless SIGNUP_ENABLED=true (restart-scoped); not asserted here
 *  - /setup        : 404 once admin id=1 is seeded (DatabaseResetCallback seeds one on startup);
 *                    not asserted here
 *  - /api/google/login/callback (bridge.html) : rendered only after Google OAuth exchange;
 *                    tested separately in GoogleLoginResourceTest; bridge.html is translated
 *                    but locale-via-cookie assertion is skipped here to avoid OAuth stubs.
 */
@QuarkusTest
class AuthI18nTest {
    // ---- Login page ----
    @Test
    void loginPageRendersGermanViaCookie() {
        given()
            .cookie("calit_lang", "de")
            .when()
            .get("/login")
            .then()
            .statusCode(200)
            .body(containsString("<html lang=\"de\""))
            .body(containsString("Anmelden"));
    }

    @Test
    void loginPageRendersEnglishByDefault() {
        given()
            .when()
            .get("/login")
            .then()
            .statusCode(200)
            .body(containsString("<html lang=\"en\""))
            .body(containsString("Sign in"));
    }
}
