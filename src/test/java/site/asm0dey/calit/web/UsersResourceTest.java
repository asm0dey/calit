package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    void createUserSendsInviteAndStoresEmail() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "bob")
            .formParam("email", "bob@example.com")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200)
            .body(containsString("bob"));

        AppUser bob = reload(AppUser.findByUsername("bob").id);
        assertThat(bob.passwordHash).as("invited user starts password-less (dormant)").isNull();
        assertThat(bob.mustChangePassword).isFalse();
        assertThat(bob.settingsComplete).isFalse();
        assertThat(bob.enabled).isTrue();
        assertThat(bob.isAdmin).isFalse();

        OwnerSettings s = OwnerSettings.forOwner(bob.id);
        assertThat(s).as("settings row pre-created so the wizard can pre-fill the email").isNotNull();
        assertThat(s.ownerEmail).isEqualTo("bob@example.com");
        assertThat(PasswordResetToken.count("userId", bob.id)).as("exactly one activation token minted").isOne();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void createUserRejectsInvalidEmail() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "carol")
            .formParam("email", "   ")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200);
        assertThat(AppUser.findByUsername("carol")).as("no user created on invalid email").isNull();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "nodot")
            .formParam("email", "a@")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200);
        assertThat(AppUser.findByUsername("nodot")).as("no user created on malformed (no-dot) email").isNull();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void createUserRejectsInvalidUsername() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "Me")
            // reserved + uppercase
            .formParam(
                    // reserved + uppercase
            "tempPassword",
                    "Temp-pw-12345")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200)
            .body(containsString("reserved"));
        assertThat(AppUser.findByUsername("me")).isNull();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void grantAndRevokeAdminSyncRoles() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "carol")
            .formParam("email", "carol@example.com")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200);
        AppUser carol = AppUser.findByUsername("carol");

        given().when().post("/me/users/" + carol.id + "/grant-admin").then().statusCode(200);
        AppUser afterGrant = reload(carol.id);
        assertThat(afterGrant.isAdmin).isTrue();
        assertThat(afterGrant.roles).isEqualTo("user,admin");

        given().when().post("/me/users/" + carol.id + "/revoke-admin").then().statusCode(200);
        AppUser afterRevoke = reload(carol.id);
        assertThat(afterRevoke.isAdmin).isFalse();
        assertThat(afterRevoke.roles).isEqualTo("user");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void lockAndUnlockTogglesEnabled() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "dave")
            .formParam("email", "dave@example.com")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200);
        AppUser dave = AppUser.findByUsername("dave");

        given().when().post("/me/users/" + dave.id + "/lock").then().statusCode(200);
        assertThat(reload(dave.id).enabled).isFalse();

        given().when().post("/me/users/" + dave.id + "/unlock").then().statusCode(200);
        assertThat(reload(dave.id).enabled).isTrue();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void unknownUserActionReturns404() {
        given().when().post("/me/users/999999/lock").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void resendInviteMintsAnotherTokenForPendingUser() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("username", "dave")
            .formParam("email", "dave@example.com")
            .when()
            .post("/me/users")
            .then()
            .statusCode(200);
        Long id = AppUser.findByUsername("dave").id;
        assertThat(PasswordResetToken.count("userId", id)).isOne();

        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/users/" + id + "/resend-invite")
            .then()
            .statusCode(200);
        assertThat(PasswordResetToken.count("userId", id)).as("resend mints a second token").isEqualTo(2);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void resendInviteRejectedForActiveUser() {
        // Admin (id 1) already has a password → not pending.
        Long adminId = AppUser.findByUsername("admin").id;
        long before = PasswordResetToken.count("userId", adminId);
        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/users/" + adminId + "/resend-invite")
            .then()
            .statusCode(200);
        assertThat(PasswordResetToken.count("userId", adminId)).as("no token minted for an active user").isEqualTo(
                before
        );
    }

    /**
     * An enabled OIDC-only account with an owner email, so resend-invite's only reason to refuse it
     * is the pending check itself.
     */
    private Long persistOidcUserWithEmail() {
        return QuarkusTransaction.requiringNew().call(() -> {
            AppUser u = AppUser.createOidcUser("sso-only", "oidc-sub-sso", false);
            u.enabled = true;
            u.persist();
            OwnerSettings s = new OwnerSettings();
            s.ownerId = u.id;
            s.ownerName = "SSO Only";
            s.ownerEmail = "sso-only@example.test";
            s.timezone = "UTC";
            s.persist();
            return u.id;
        });
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void resendInviteRejectedForOidcUser() {
        var id = persistOidcUserWithEmail();
        given()
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/users/" + id + "/resend-invite")
            .then()
            .statusCode(200);
        assertThat(PasswordResetToken.count("userId", id)).as("no token minted for an SSO-only account").isZero();
    }

    @Test
    @TestSecurity(user = "admin", roles = {"user", "admin"})
    void oidcUserListedWithoutResendInviteButton() {
        var id = persistOidcUserWithEmail();
        given()
            .when()
            .get("/me/users")
            .then()
            .statusCode(200)
            .body(containsString("sso-only"))
            .body(not(containsString("/me/users/" + id + "/resend-invite")))
            // users_status_pending; only the seeded admin (password user) shares the page
            .body(not(containsString("Awaiting activation")));
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
        var ok = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "erin")
            .formParam("j_password", "Erin-pw-12345")
            .redirects()
            .follow(false)
            .when()
            .post("/j_security_check");
        assertThat(ok.statusCode()).isEqualTo(302);
        // Lock the user.
        QuarkusTransaction.requiringNew().run(() -> {
            AppUser u = AppUser.findByUsername("erin");
            u.enabled = false;
        });
        // Re-login now fails → redirect to the form error page (/login?error=true).
        var denied = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "erin")
            .formParam("j_password", "Erin-pw-12345")
            .redirects()
            .follow(false)
            .when()
            .post("/j_security_check");
        assertThat(denied.statusCode()).isEqualTo(302);
        assertThat(denied.getHeader("Location"))
            .as("locked user login should redirect to the error page, got " + denied.getHeader("Location"))
            .contains("error");
    }
}
