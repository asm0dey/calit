package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;

/**
 * HTTP-level coverage for Task 7's routes ({@code /me/settings/delete}, {@code
 * /me/users/{id}/delete}): CSRF-exempt in {@code %test} (see CLAUDE.md), so these post without a
 * token. {@link site.asm0dey.calit.privacy.AccountDeletionTest} covers the service-level cascade
 * behaviour; this file covers confirmation, last-admin guards, and the through-{@code /logout}
 * redirect on self-deletion.
 */
@QuarkusTest
class AccountDeletionRoutesTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    /** A second, non-admin, password-having owner — deleting them is always legal. */
    private Long seedOwnerWithPassword(String username, String rawPassword) {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create(username, HASHER.hash(rawPassword), false);
            u.settingsComplete = true;
            u.persist();
            OwnerSettings.seed(u.id, username + "@example.com");
            return u.id;
        });
    }

    /** A Google-only owner with no password — confirms by typing their own username instead. */
    private Long seedGoogleOnlyOwner(String username) {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.createGoogleUser(username, "google-sub-" + username);
            u.settingsComplete = true;
            u.persist();
            OwnerSettings.seed(u.id, username + "@example.com");
            return u.id;
        });
    }

    @Test
    @TestSecurity(
            user = "bob",
            roles = {"user"})
    void confirmPageShowsPasswordFieldForAPasswordAccount() {
        seedOwnerWithPassword("bob", "Bob-pw-12345");
        given().when()
                .get("/me/settings/delete")
                .then()
                .statusCode(200)
                .body(containsString("Delete your account?"))
                .body(containsString("type=\"password\" name=\"confirmation\""));
    }

    @Test
    @TestSecurity(
            user = "bob",
            roles = {"user"})
    void confirmPageShowsUsernameFieldForAPasswordlessAccount() {
        seedGoogleOnlyOwner("bob");
        given().when()
                .get("/me/settings/delete")
                .then()
                .statusCode(200)
                .body(containsString("type=\"text\" name=\"confirmation\""));
    }

    @Test
    @TestSecurity(
            user = "bob",
            roles = {"user"})
    void wrongPasswordDoesNotDeleteTheAccount() {
        seedOwnerWithPassword("bob", "Bob-pw-12345");
        given().contentType("application/x-www-form-urlencoded")
                .formParam("confirmation", "not-the-password")
                .when()
                .post("/me/settings/delete")
                .then()
                .statusCode(200)
                .body(containsString("Your account was not deleted"));
        assertEquals(1, AppUser.count("username", "bob"), "the account must survive a wrong confirmation");
    }

    @Test
    @TestSecurity(
            user = "bob",
            roles = {"user"})
    void correctPasswordDeletesAndRedirectsThroughLogout() {
        seedOwnerWithPassword("bob", "Bob-pw-12345");
        given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("confirmation", "Bob-pw-12345")
                .when()
                .post("/me/settings/delete")
                .then()
                .statusCode(303)
                .header("Location", containsString("/logout"));
        assertNull(AppUser.findByUsername("bob"), "the account must be gone");
    }

    @Test
    @TestSecurity(
            user = "bob",
            roles = {"user"})
    void correctUsernameDeletesAPasswordlessAccount() {
        seedGoogleOnlyOwner("bob");
        given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("confirmation", "bob")
                .when()
                .post("/me/settings/delete")
                .then()
                .statusCode(303)
                .header("Location", containsString("/logout"));
        assertNull(AppUser.findByUsername("bob"));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void theLastEnabledAdminCannotSelfDelete() {
        // DatabaseResetCallback seeds exactly one admin ("admin", "testpass"), always id 1.
        given().contentType("application/x-www-form-urlencoded")
                .formParam("confirmation", "testpass")
                .when()
                .post("/me/settings/delete")
                .then()
                .statusCode(200)
                .body(containsString("last enabled admin"));
        assertEquals(1, AppUser.count("username", "admin"), "the last admin must survive");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void adminDeletesAnotherUser() {
        var id = seedOwnerWithPassword("carol", "Carol-pw-12345");
        given().when()
                .post("/me/users/" + id + "/delete")
                .then()
                .statusCode(200)
                .body(containsString("Users"));
        assertNull(AppUser.findByUsername("carol"));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void adminCannotDeleteTheLastEnabledAdminThroughUsersRoute() {
        given().when()
                .post("/me/users/1/delete")
                .then()
                .statusCode(200)
                .body(containsString("Cannot delete the last enabled admin"));
        assertEquals(1, AppUser.count("id", 1L));
    }

    @Test
    void adminDeletingThemselvesIsRedirectedThroughLogout() {
        // Real second admin (not id 1) so this is not the last-enabled-admin case.
        Long id = QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.create("second-admin", HASHER.hash("Second-pw-12345"), true);
            u.settingsComplete = true;
            u.persist();
            OwnerSettings.seed(u.id, "second-admin@example.com");
            return u.id;
        });
        assertTrue(id > 1, "must not collide with the seeded admin (id 1)");

        var cookie = given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "second-admin")
                .formParam("j_password", "Second-pw-12345")
                .when()
                .post("/j_security_check")
                .then()
                .extract()
                .cookie("quarkus-credential");

        given().cookie("quarkus-credential", cookie)
                .redirects()
                .follow(false)
                .when()
                .post("/me/users/" + id + "/delete")
                .then()
                .statusCode(303)
                .header("Location", containsString("/logout"));
        assertNull(AppUser.findByUsername("second-admin"));
    }
}
