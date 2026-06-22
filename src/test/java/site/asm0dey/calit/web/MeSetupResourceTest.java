package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class MeSetupResourceTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Inject
    EntityManager em;

    @Transactional
    Long seed(String username, boolean mustChange) {
        AppUser u = AppUser.create(username, HASHER.hash("Initial-pw-12345"), false);
        u.mustChangePassword = mustChange;
        u.settingsComplete = false;
        u.persist();
        return u.id;
    }

    /** Reload from the DB, bypassing the test thread's first-level cache (mutating POST commits in its own tx). */
    @Transactional
    AppUser reload(Long id) {
        em.clear();
        return AppUser.findById(id);
    }

    @Test
    @TestSecurity(user = "wiz1", roles = {"user"})
    void getRendersWizardWithPasswordStepWhenForced() {
        seed("wiz1", true);
        given().when().get("/me/setup").then().statusCode(200).body(containsString("New password"));
    }

    @Test
    @TestSecurity(user = "wiz2", roles = {"user"})
    void postCompletesPasswordAndSettings() {
        Long id = seed("wiz2", true);
        given().contentType("application/x-www-form-urlencoded")
            .formParam("newPassword", "Brand-new-pw-12345")
            .formParam("ownerName", "Wiz Two")
            .formParam("ownerEmail", "wiz2@example.com")
            .formParam("timezone", "Europe/Amsterdam")
            .redirects().follow(false)
            .when().post("/me/setup").then().statusCode(303);

        AppUser after = reload(id);
        assertFalse(after.mustChangePassword);
        assertTrue(after.settingsComplete);
        assertTrue(HASHER.verify("Brand-new-pw-12345", after.passwordHash),
            "password should have been updated");

        OwnerSettings s = OwnerSettings.forOwner(id);
        assertNotNull(s);
        assertEquals("Wiz Two", s.ownerName);
        assertEquals("wiz2@example.com", s.ownerEmail);
        assertEquals("Europe/Amsterdam", s.timezone);
    }

    @Test
    @TestSecurity(user = "wiz3", roles = {"user"})
    void postSkipsPasswordWhenNotForced() {
        Long id = seed("wiz3", false); // self-service user: no forced reset
        given().contentType("application/x-www-form-urlencoded")
            .formParam("ownerName", "Wiz Three")
            .formParam("ownerEmail", "wiz3@example.com")
            .formParam("timezone", "Europe/Amsterdam")
            .redirects().follow(false)
            .when().post("/me/setup").then().statusCode(303);

        AppUser after = reload(id);
        assertTrue(after.settingsComplete);
        assertTrue(HASHER.verify("Initial-pw-12345", after.passwordHash), "password unchanged");
    }

    @Test
    @TestSecurity(user = "wiz4", roles = {"user"})
    void notForcedUserCannotChangePasswordViaWizard() {
        Long id = seed("wiz4", false);
        // Even if a non-forced user posts a newPassword, the wizard must ignore it (password-change
        // path is structurally gated on mustChangePassword).
        given().contentType("application/x-www-form-urlencoded")
            .formParam("newPassword", "Sneaky-new-pw-12345")
            .formParam("ownerName", "Wiz Four")
            .formParam("ownerEmail", "wiz4@example.com")
            .formParam("timezone", "Europe/Amsterdam")
            .redirects().follow(false)
            .when().post("/me/setup").then().statusCode(303);

        AppUser after = reload(id);
        assertTrue(after.settingsComplete);
        assertTrue(HASHER.verify("Initial-pw-12345", after.passwordHash),
            "non-forced user's password must be unchanged even when newPassword is supplied");
        assertFalse(HASHER.verify("Sneaky-new-pw-12345", after.passwordHash));
    }

    @Test
    @TestSecurity(user = "wiz5", roles = {"user"})
    void forcedUserWithBlankPasswordReRendersAndDoesNotComplete() {
        Long id = seed("wiz5", true);
        given().contentType("application/x-www-form-urlencoded")
            // newPassword omitted (blank) while mustChangePassword is set.
            .formParam("ownerName", "Wiz Five")
            .formParam("ownerEmail", "wiz5@example.com")
            .formParam("timezone", "Europe/Amsterdam")
            .when().post("/me/setup")
            .then().statusCode(200).body(containsString("Please choose a new password"));

        AppUser after = reload(id);
        assertTrue(after.mustChangePassword, "still forced — onboarding not advanced");
        assertFalse(after.settingsComplete, "settings must not be marked complete on the error path");
    }
}
