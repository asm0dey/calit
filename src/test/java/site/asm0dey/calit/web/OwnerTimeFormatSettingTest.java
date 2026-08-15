package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * The host's 12h/24h preference persists, and an unknown submitted value falls back to "auto"
 * rather than reaching the database (same guard shape as the locale field).
 */
@QuarkusTest
class OwnerTimeFormatSettingTest {

    /** DatabaseResetCallback reseeds per test and the admin user is always id 1. */
    private static final long ADMIN_ID = 1L;

    private void post(String timeFormat) {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("timeFormat", timeFormat)
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);
    }

    private String stored() {
        return QuarkusTransaction.requiringNew().call(() -> OwnerSettings.forOwner(ADMIN_ID).timeFormat);
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void savesAnExplicitTwelveHourPreference() {
        post("h12");
        assertEquals("h12", stored());
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void savesAnExplicitTwentyFourHourPreference() {
        post("h23");
        assertEquals("h23", stored());
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void rejectsAnUnknownValueAndFallsBackToAuto() {
        post("h11-and-a-half");
        assertEquals("auto", stored());
    }
}
