package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;

@QuarkusTest
class TimezoneAutodetectTest {

    @Transactional
    void seedNotOnboarded(String username) {
        if (AppUser.findByUsername(username) == null) {
            AppUser u = AppUser.create(username, new PasswordHasher().hash("Initial-pw-12345"), false);
            u.mustChangePassword = false;
            u.settingsComplete = false; // -> first-login wizard renders the no-settings timezone select
            u.persist();
        }
    }

    @Test
    @TestSecurity(
            user = "tzwiz",
            roles = {"user"})
    void firstRunWizardDefaultsUtcAndAutodetects() {
        seedNotOnboarded("tzwiz");
        given().when()
                .get("/me/setup")
                .then()
                .statusCode(200)
                .body(containsString("data-tz-autodetect"))
                .body(containsString("value=\"UTC\" selected"))
                .body(containsString("CALIT_TZ_AUTODETECT"));
    }
}
