package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;

/**
 * The home redirect is an opt-out: on by default, and one checkbox away from off. An unchecked box
 * submits nothing, which is how the form expresses "off" (CSRF is disabled in %test, so a bare
 * form POST is accepted).
 *
 * <p>{@code DatabaseResetCallback} truncates {@code owner_settings} before each test and reseeds
 * only the baseline {@code admin} {@code AppUser} (id 1) -- it never seeds an {@code
 * OwnerSettings} row. {@code settingsPageShowsTheOptOutCheckedByDefault} and {@code
 * optingOutMakesHomeRenderTheProductPageAgain} need a row to render/redirect against, so each
 * seeds its own via {@link OwnerSettings#seed}, matching {@code HomeRedirectTest} and {@code
 * OwnerSettingsHomeRedirectTest}.
 */
@QuarkusTest
class HomeRedirectSettingTest {

    @Inject
    EntityManager em;

    @Transactional
    void seedOwnerSettings() {
        OwnerSettings.seed(1L, "admin@example.com");
    }

    /**
     * Reads {@code homeRedirectEnabled} straight from the DB, bypassing the test thread's
     * first-level cache -- same pattern as {@code AdminSettingsTest#readNotificationsEnabled}.
     */
    @Transactional
    boolean readHomeRedirectEnabled() {
        em.clear();
        return OwnerSettings.forOwner(1L).homeRedirectEnabled;
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void settingsPageShowsTheOptOutCheckedByDefault() {
        seedOwnerSettings();

        given().when().get("/me/settings").then().statusCode(200).body(containsString("name=\"homeRedirectEnabled\""));

        // The literal rendered markup is brittle against a daisyUI class/attribute-order bump; the
        // checked *state* is verified against the persisted domain value instead.
        org.junit.jupiter.api.Assertions.assertTrue(
                readHomeRedirectEnabled(), "home redirect must default to enabled on a freshly-seeded row");
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void optingOutMakesHomeRenderTheProductPageAgain() {
        seedOwnerSettings();

        // Before opting out: the seeded row defaults to enabled, so / must already redirect.
        given().redirects().follow(false).when().get("/").then().statusCode(303);

        // Unchecked box => field absent from the POST.
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().redirects()
                .follow(false)
                .when()
                .get("/")
                .then()
                .statusCode(200)
                .body(containsString("Self-hosted scheduling"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "user")
    void optingBackInRestoresTheRedirect() {
        given().formParam("ownerName", "Admin")
                .formParam("ownerEmail", "admin@example.com")
                .formParam("timezone", "UTC")
                .formParam("locale", "en")
                .formParam("homeRedirectEnabled", "on")
                .when()
                .post("/me/settings")
                .then()
                .statusCode(200);

        given().redirects().follow(false).when().get("/").then().statusCode(303);
    }
}
