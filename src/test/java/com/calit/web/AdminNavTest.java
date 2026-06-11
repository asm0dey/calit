package com.calit.web;

import com.calit.user.AppUser;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AdminNavTest {

    @Transactional
    void seedPlainUser(String username) {
        if (AppUser.findByUsername(username) == null) {
            AppUser u = AppUser.create(username, "x", false); // role "user", not admin
            u.mustChangePassword = false;
            u.settingsComplete = true; // onboarded — reaches /me without the wizard redirect
            u.persist();
        }
    }

    /** The admin dashboard nav must expose the admin-only Users management link. */
    @Test
    void adminDashboardShowsUsersNavLink() {
        given()
            .cookie("quarkus-credential", FormAuth.login()) // baseline admin (role user,admin)
            .when().get("/me")
            .then().statusCode(200)
            .body(containsString("href=\"/me/users\""));
    }

    /** A non-admin user reaches /me (role "user") but the Users link is gated out by {#if isAdmin}. */
    @Test
    @TestSecurity(user = "plainuser", roles = {"user"})
    void nonAdminDashboardHidesUsersNavLink() {
        seedPlainUser("plainuser"); // MeOwnerFilter resolves CurrentOwner from this row
        given()
            .when().get("/me")
            .then().statusCode(200)
            .body(not(containsString("href=\"/me/users\"")));
    }
}
