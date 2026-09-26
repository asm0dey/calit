package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * "/" is the instance entrance: a signed-in visitor goes to their dashboard. Pages that belong to
 * a person never redirect, however you are signed in -- checking your own public page as a guest
 * sees it is the main reason to visit it.
 *
 * <p>{@code DatabaseResetCallback} truncates {@code owner_settings} before each test and reseeds
 * only the baseline {@code admin} {@code AppUser} (id 1) -- it never seeds an {@code
 * OwnerSettings} row (see {@code EmailMissingOwnerSettingsTest} for a test that deliberately
 * exercises that missing-row state). Tests here that need a redirect-eligible owner seed their own
 * row via {@link OwnerSettings#seed}, matching {@code OwnerSettingsHomeRedirectTest}.
 */
@QuarkusTest
class HomeRedirectTest {
    @Transactional
    void seedOwnerSettings() {
        OwnerSettings.seed(1L, "admin@example.com");
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void signedInVisitorIsSentToTheirDashboard() {
        seedOwnerSettings();

        given()
            .redirects()
            .follow(false)
            .when()
            .get("/")
            .then()
            .statusCode(303)
            .header("Location", endsWith("/me"))
            // A shared cache must never replay this at an anonymous visitor.
            .header("Cache-Control", equalTo("no-store"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void signedInVisitorWithNoSettingsRowIsNotRedirected() {
        // Deliberately NOT seeded: a user with no OwnerSettings row fails toward today's behaviour
        // rather than being bounced mid-bootstrap. The preference lookup is a single subquery over
        // app_user, so this is the branch that would break if that query ever matched too broadly.
        given()
            .redirects()
            .follow(false)
            .when()
            .get("/")
            .then()
            .statusCode(200)
            .body(containsString("Self-hosted scheduling"));
    }

    @Test
    void anonymousVisitorStillGetsTheProductPage() {
        given()
            .redirects()
            .follow(false)
            .when()
            .get("/")
            .then()
            .statusCode(200)
            .body(containsString("Self-hosted scheduling"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void ownLandingPageIsNeverRedirectedAway() {
        // Seed a settings row so /admin renders the real landing page (Templates.landing) rather
        // than the not-ready placeholder -- the placeholder is a 200 for ANY visitor with a
        // missing-settings owner, signed in or not, so it wouldn't actually prove that being
        // signed in leaves this page alone.
        seedOwnerSettings();
        // /{username} belongs to a person; being signed in must not take you off it.
        given().redirects().follow(false).when().get("/admin").then().statusCode(200);
    }
}
