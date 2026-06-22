package site.asm0dey.calit.user;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class EnabledUserAugmentorTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    private void upsert(String username, boolean enabled) {
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.findByUsername(username);
            if (u == null) {
                u = AppUser.create(username, HASHER.hash("pw12345"), true);
            }
            u.enabled = enabled;
            u.mustChangePassword = false;
            u.settingsComplete = true; // onboarded — reaches /me without the wizard redirect
            u.persist();
        });
    }

    @Test
    void disabledUserCookieIsRejected() {
        upsert("lockme", true);
        String cookie = given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "lockme")
                .formParam("j_password", "pw12345")
                .when().post("/j_security_check")
                .then().statusCode(302).extract().cookie("quarkus-credential");

        // Sanity: cookie works while enabled.
        given().cookie("quarkus-credential", cookie)
                .when().get("/me")
                .then().statusCode(200).body(containsString("Dashboard"));

        // Disable the user; the still-valid cookie must now be rejected.
        upsert("lockme", false);
        given().redirects().follow(false)
                .cookie("quarkus-credential", cookie)
                .when().get("/me")
                .then().statusCode(302)
                .header("Location", containsString("/login"));
    }
}
