package site.asm0dey.calit.web;

import site.asm0dey.calit.user.AppUser;
import site.asm0dey.calit.user.PasswordHasher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

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
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void listShowsExistingUsers() {
        given().when().get("/me/users").then().statusCode(200).body(containsString("Users"));
    }

    @Test
    @TestSecurity(user = "alice", roles = {"user"})
    void nonAdminIsForbidden() {
        given().when().get("/me/users").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void createUserPersistsTempUser() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "bob").formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users").then().statusCode(200).body(containsString("bob"));
        AppUser bob = AppUser.findByUsername("bob");
        assertNotNull(bob);
        assertTrue(bob.mustChangePassword);
        assertFalse(bob.settingsComplete);
        assertTrue(bob.enabled);
        assertFalse(bob.isAdmin);
        assertEquals("user", bob.roles);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void createUserRejectsInvalidUsername() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "Me").formParam("tempPassword", "Temp-pw-12345") // reserved + uppercase
            .when().post("/me/users").then().statusCode(200).body(containsString("reserved"));
        assertNull(AppUser.findByUsername("me"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void grantAndRevokeAdminSyncRoles() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "carol").formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users").then().statusCode(200);
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
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void lockAndUnlockTogglesEnabled() {
        given().contentType("application/x-www-form-urlencoded")
            .formParam("username", "dave").formParam("tempPassword", "Temp-pw-12345")
            .when().post("/me/users").then().statusCode(200);
        AppUser dave = AppUser.findByUsername("dave");

        given().when().post("/me/users/" + dave.id + "/lock").then().statusCode(200);
        assertFalse(reload(dave.id).enabled);

        given().when().post("/me/users/" + dave.id + "/unlock").then().statusCode(200);
        assertTrue(reload(dave.id).enabled);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void unknownUserActionReturns404() {
        given().when().post("/me/users/999999/lock").then().statusCode(404);
    }

    @Test
    void lockedUserCannotLogIn() {
        // Real auth chain (NOT @TestSecurity): exercise AppUserIdentityProvider + EnabledUserAugmentor.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.create("erin", HASHER.hash("Erin-pw-12345"), false);
            u.enabled = true; u.mustChangePassword = false; u.settingsComplete = true;
            u.persist();
        });
        // Enabled → login succeeds (302).
        var ok = given().contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "erin").formParam("j_password", "Erin-pw-12345")
            .redirects().follow(false).when().post("/j_security_check");
        assertEquals(302, ok.statusCode());
        // Lock the user.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.findByUsername("erin"); u.enabled = false;
        });
        // Re-login now fails → redirect to the form error page (/login?error=true).
        var denied = given().contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "erin").formParam("j_password", "Erin-pw-12345")
            .redirects().follow(false).when().post("/j_security_check");
        assertEquals(302, denied.statusCode());
        assertTrue(denied.getHeader("Location").contains("error"),
            "locked user login should redirect to the error page, got " + denied.getHeader("Location"));
    }
}
