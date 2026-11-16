package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Task 9b: verifies that admin UI pages render in German when the owner's locale is set to "de",
 * regardless of the calit_lang cookie.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = "user")
class AdminI18nTest {
    /**
     * Set the admin owner's locale to "de" before each test.
     */
    @BeforeEach
    void setGermanLocale() {
        given()
            .formParam("ownerName", "Admin")
            .formParam("ownerEmail", "admin@example.com")
            .formParam("timezone", "UTC")
            .formParam("locale", "de")
            .when()
            .post("/me/settings")
            .then()
            .statusCode(200);
    }

    @Test
    void dashboardRendersInGerman() {
        given()
            // cookie must NOT override owner locale
            .cookie("calit_lang", "en")
            .when()
            .get("/me")
            .then()
            .statusCode(200)
            .body(containsString("lang=\"de\""))
            .body(containsString("Dashboard"))
            .body(containsString("Bevorstehende Termine"));
    }

    @Test
    void meetingTypesPageContainsCopiedLinkSpan() {
        // The server-rendered span with the translated "Link kopiert" text must be present
        // so that the JS toast reads its localized textContent rather than hardcoded English.
        given()
            .cookie("calit_lang", "en")
            .when()
            .get("/me/meeting-types")
            .then()
            .statusCode(200)
            .body(containsString("lang=\"de\""))
            .body(containsString("Link kopiert"));
    }
}
