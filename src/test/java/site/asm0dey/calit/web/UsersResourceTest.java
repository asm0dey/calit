package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.domain.OwnerSettings;
import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;
import site.asm0dey.calit.user.PasswordResetToken;

@QuarkusTest
class UsersResourceTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Inject
    EntityManager em;

    /**
     * Reload a user straight from the DB, bypassing the test thread's first-level cache. The
     * mutating POST commits in its own request transaction; a plain findById here would return
     * the stale entity cached by the earlier find(...) read in this non-transactional method.
     */
    @Transactional
    AppUser reload(Long id) {
        em.clear();
        return AppUser.findById(id);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void listShowsExistingUsers() {
        given().when().get("/me/users").then().statusCode(200).body(containsString("Users"));
    }

    @Test
    @TestSecurity(
            user = "alice",
            roles = {"user"})
    void nonAdminIsForbidden() {
        given().when().get("/me/users").then().statusCode(403);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void createUserSendsInviteAndStoresEmail() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "bob")
                .formParam("email", "bob@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200)
                .body(containsString("bob"));

        AppUser bob = reload(AppUser.findByUsername("bob").id);
        assertNull(bob.passwordHash, "invited user starts password-less (dormant)");
        assertFalse(bob.mustChangePassword);
        assertFalse(bob.settingsComplete);
        assertTrue(bob.enabled);
        assertFalse(bob.isAdmin);

        OwnerSettings s = OwnerSettings.forOwner(bob.id);
        assertNotNull(s, "settings row pre-created so the wizard can pre-fill the email");
        assertEquals("bob@example.com", s.ownerEmail);
        assertEquals(1, PasswordResetToken.count("userId", bob.id), "exactly one activation token minted");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void createUserRejectsInvalidEmail() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "carol")
                .formParam("email", "   ")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        assertNull(AppUser.findByUsername("carol"), "no user created on invalid email");

        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "nodot")
                .formParam("email", "a@")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        assertNull(AppUser.findByUsername("nodot"), "no user created on malformed (no-dot) email");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void createUserRejectsInvalidUsername() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "Me")
                .formParam("tempPassword", "Temp-pw-12345") // reserved + uppercase
                .when()
                .post("/me/users")
                .then()
                .statusCode(200)
                .body(containsString("reserved"));
        assertNull(AppUser.findByUsername("me"));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void grantAndRevokeAdminSyncRoles() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "carol")
                .formParam("email", "carol@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        AppUser carol = AppUser.findByUsername("carol");

        given().when().post("/me/users/" + carol.id + "/grant-admin").then().statusCode(200);
        AppUser afterGrant = reload(carol.id);
        assertTrue(afterGrant.isAdmin);
        assertEquals("user,admin", afterGrant.roles);

        given().when().post("/me/users/" + carol.id + "/revoke-admin").then().statusCode(200);
        AppUser afterRevoke = reload(carol.id);
        assertFalse(afterRevoke.isAdmin);
        assertEquals("user", afterRevoke.roles);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void lockAndUnlockTogglesEnabled() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "dave")
                .formParam("email", "dave@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        AppUser dave = AppUser.findByUsername("dave");

        given().when().post("/me/users/" + dave.id + "/lock").then().statusCode(200);
        assertFalse(reload(dave.id).enabled);

        given().when().post("/me/users/" + dave.id + "/unlock").then().statusCode(200);
        assertTrue(reload(dave.id).enabled);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void unknownUserActionReturns404() {
        given().when().post("/me/users/999999/lock").then().statusCode(404);
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void resendInviteMintsAnotherTokenForPendingUser() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("username", "dave")
                .formParam("email", "dave@example.com")
                .when()
                .post("/me/users")
                .then()
                .statusCode(200);
        Long id = AppUser.findByUsername("dave").id;
        assertEquals(1, PasswordResetToken.count("userId", id));

        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/users/" + id + "/resend-invite")
                .then()
                .statusCode(200);
        assertEquals(2, PasswordResetToken.count("userId", id), "resend mints a second token");
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void resendInviteRejectedForActiveUser() {
        // Admin (id 1) already has a password → not pending.
        Long adminId = AppUser.findByUsername("admin").id;
        long before = PasswordResetToken.count("userId", adminId);
        given().contentType("application/x-www-form-urlencoded")
                .when()
                .post("/me/users/" + adminId + "/resend-invite")
                .then()
                .statusCode(200);
        assertEquals(before, PasswordResetToken.count("userId", adminId), "no token minted for an active user");
    }

    @Test
    void lockedUserCannotLogIn() {
        // Real auth chain (NOT @TestSecurity): exercise AppUserIdentityProvider + EnabledUserAugmentor.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.create("erin", HASHER.hash("Erin-pw-12345"), false);
            u.enabled = true;
            u.mustChangePassword = false;
            u.settingsComplete = true;
            u.persist();
        });
        // Enabled → login succeeds (302).
        var ok = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "erin")
                .formParam("j_password", "Erin-pw-12345")
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check");
        assertEquals(302, ok.statusCode());
        // Lock the user.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.findByUsername("erin");
            u.enabled = false;
        });
        // Re-login now fails → redirect to the form error page (/login?error=true).
        var denied = given().contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "erin")
                .formParam("j_password", "Erin-pw-12345")
                .redirects()
                .follow(false)
                .when()
                .post("/j_security_check");
        assertEquals(302, denied.statusCode());
        assertTrue(
                denied.getHeader("Location").contains("error"),
                "locked user login should redirect to the error page, got " + denied.getHeader("Location"));
    }
}
