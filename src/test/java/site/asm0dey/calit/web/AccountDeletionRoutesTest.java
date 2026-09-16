package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.privacy.PrivacyService;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;

/**
 * HTTP-level coverage for Task 7's routes ({@code /me/settings/delete}, {@code
 * /me/users/{id}/delete}): CSRF-exempt in {@code %test} (see CLAUDE.md), so these post without a
 * token. {@link site.asm0dey.calit.privacy.AccountDeletionTest} covers the service-level cascade
 * behaviour; this file covers confirmation, last-admin guards, the self-deletion refusal on the
 * admin route (fix round 1, Finding 1 / R15), and the deleted-username tombstone closing the
 * account-takeover window through a stale persistent-login cookie (fix round 1, Finding 2 / R16).
 */
@QuarkusTest
class AccountDeletionRoutesTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Inject
    PrivacyService privacy;

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

    /**
     * Finding 1 / R15: an admin can never delete THEIR OWN account through {@code
     * /me/users/{id}/delete} — that would be a one-click, no-re-authentication deletion, unlike
     * self-serve deletion at {@code /me/settings/delete} which re-verifies a credential first. The
     * route refuses and points at self-serve deletion instead; the account survives.
     *
     * <p>Note: because this same self-guard fires first, the last-admin guard inside {@code
     * privacy.deleteAccount} can no longer be reached through THIS route for any target — a
     * different target who is also "the last enabled admin" is a contradiction once the acting
     * admin (who must themselves be enabled to have authenticated at all) is excluded by the
     * self-guard. The last-admin throw itself is still covered directly at the service level by
     * {@link site.asm0dey.calit.privacy.AccountDeletionTest#theLastEnabledAdminCannotBeDeleted()}.
     */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void adminCannotDeleteThemselvesViaUsersRoute() {
        given().when()
                .post("/me/users/1/delete")
                .then()
                .statusCode(200)
                .body(containsString("own account"))
                .body(containsString("Settings"));
        assertEquals(1, AppUser.count("id", 1L), "self-delete via the admin route must be refused");
    }

    /** R17: deleting an id with no backing row 404s via the existing {@code requireUser} pattern, no audit event. */
    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void adminDeleteOfUnknownUserReturns404() {
        given().when().post("/me/users/999999/delete").then().statusCode(404);
    }

    /**
     * Finding 2 / R16: deleting an account tombstones its username forever, closing the
     * account-takeover window through Quarkus form-auth's persistent-login cookie (which carries
     * only a username, not a user id, and renews itself on every restored request even once the
     * backing account is gone).
     */
    @Test
    void deletedUsernameCannotBeReusedAndTheOldCookieIsRejected() {
        // Real form login (not @TestSecurity) so a genuine persistent-login cookie is minted.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.create("erin", HASHER.hash("Erin-pw-12345"), false);
            u.settingsComplete = true;
            u.persist();
            OwnerSettings.seed(u.id, "erin@example.com");
        });
        var cookie = given().redirects()
                .follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "erin")
                .formParam("j_password", "Erin-pw-12345")
                .when()
                .post("/j_security_check")
                .then()
                .extract()
                .cookie("quarkus-credential");

        // The cookie genuinely works before deletion.
        given().cookie("quarkus-credential", cookie).when().get("/me").then().statusCode(200);

        Long erinId = AppUser.findByUsername("erin").id;
        QuarkusTransaction.requiringNew().run(() -> privacy.deleteAccount(erinId));

        // (a) the tombstoned username can never be re-registered, through any username-choosing path.
        given().cookie("quarkus-credential", FormAuth.login())
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "erin")
                .formParam("email", "erin2@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200)
                .body(containsString("already taken"));
        assertNull(AppUser.findByUsername("erin"), "the tombstoned username must not be recreated");

        // (b) the old, still-cryptographically-valid cookie no longer reaches a protected page.
        var stale = given().cookie("quarkus-credential", cookie)
                .redirects()
                .follow(false)
                .when()
                .get("/me")
                .then()
                .extract();
        assertTrue(
                stale.statusCode() == 302 || stale.statusCode() == 401,
                "a deleted account's cookie must not reach /me, got " + stale.statusCode());
        if (stale.statusCode() == 302) {
            assertTrue(
                    stale.header("Location").contains("/login"),
                    "expected a redirect to /login, got " + stale.header("Location"));
        }
    }
}
