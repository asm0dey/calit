package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.user.AppUser;

@QuarkusTest
class AdminNavTest {
    @Transactional
    void seedPlainUser(String username) {
        if (AppUser.findByUsername(username) == null) {
            // role "user", not admin
            AppUser u = AppUser.create(username, "x", false);
            u.mustChangePassword = false;
            // onboarded — reaches /me without the wizard redirect
            u.settingsComplete = true;
            u.persist();
        }
    }

    /**
     * The admin dashboard nav must expose the admin-only Users management link.
     */
    @Test
    void adminDashboardShowsUsersNavLink() {
        given()
            // baseline admin (role user,admin)
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me")
            .then()
            .statusCode(200)
            .body(containsString("href=\"/me/users\""));
    }

    /**
     * A non-admin user reaches /me (role "user") but the Users link is gated out by {#if isAdmin}.
     */
    @Test
    @TestSecurity(user = "plainuser", roles = {"user"})
    void nonAdminDashboardHidesUsersNavLink() {
        // MeOwnerFilter resolves CurrentOwner from this row
        seedPlainUser("plainuser");
        given().when().get("/me").then().statusCode(200).body(not(containsString("href=\"/me/users\"")));
    }
}
