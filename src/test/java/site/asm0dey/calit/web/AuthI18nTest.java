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
        given().cookie("calit_lang", "de")
                .when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("<html lang=\"de\""))
                .body(containsString("Anmelden"));
    }

    @Test
    void loginPageRendersEnglishByDefault() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("<html lang=\"en\""))
                .body(containsString("Sign in"));
    }

    @Test
    void loginPageGermanTitle() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("<title>Anmelden — calit</title>"));
    }

    @Test
    void loginPageEnglishTitle() {
        given().when().get("/login").then().statusCode(200).body(containsString("<title>Sign in — calit</title>"));
    }

    @Test
    void loginPageGermanLabels() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("Benutzername"))
                .body(containsString("Passwort"))
                .body(containsString("Passwort vergessen?"));
    }

    @Test
    void loginPageGermanGoogleNotice() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/login?notice=google_signup_disabled")
                .then()
                .statusCode(200)
                .body(containsString("Registrierungen sind deaktiviert"));
    }

    @Test
    void loginPageGermanGenericGoogleNotice() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/login?notice=google")
                .then()
                .statusCode(200)
                .body(containsString("Google-Anmeldung konnte nicht abgeschlossen werden"));
    }

    // ---- Forgot password page ----

    @Test
    void forgotPageRendersGermanViaCookie() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("<html lang=\"de\""))
                .body(containsString("Passwort vergessen"));
    }

    @Test
    void forgotPageRendersEnglishByDefault() {
        given().when()
                .get("/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("<html lang=\"en\""))
                .body(containsString("Forgot password"));
    }

    @Test
    void forgotPageGermanTitle() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("<title>Passwort vergessen — calit</title>"));
    }

    @Test
    void forgotPageGermanSubmitButton() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/forgot-password")
                .then()
                .statusCode(200)
                .body(containsString("Zurücksetzen-Link senden"));
    }

    // ---- Reset password page ----

    @Test
    void resetPageRendersGermanViaCookie() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/reset-password?token=sometoken")
                .then()
                .statusCode(200)
                .body(containsString("<html lang=\"de\""))
                .body(containsString("Passwort zurücksetzen"));
    }

    @Test
    void resetPageRendersEnglishByDefault() {
        given().when()
                .get("/reset-password?token=sometoken")
                .then()
                .statusCode(200)
                .body(containsString("<html lang=\"en\""))
                .body(containsString("Reset password"));
    }

    @Test
    void resetPageGermanTitle() {
        given().cookie("calit_lang", "de")
                .when()
                .get("/reset-password?token=sometoken")
                .then()
                .statusCode(200)
                .body(containsString("<title>Passwort zurücksetzen — calit</title>"));
    }

    @Test
    void resetPageInvalidLinkGerman() {
        // No token → expired/invalid branch
        given().cookie("calit_lang", "de")
                .when()
                .get("/reset-password")
                .then()
                .statusCode(200)
                .body(containsString("ungültig oder abgelaufen"))
                .body(containsString("Neuen Link anfordern"));
    }
}
