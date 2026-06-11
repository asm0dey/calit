package com.calit.web;

import com.calit.user.AppUser;
import com.calit.user.PasswordHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class MeOwnerFilterWizardTest {

    private static final PasswordHasher HASHER = new PasswordHasher();

    @Transactional
    void seed(String username, boolean settingsComplete) {
        AppUser u = AppUser.create(username, HASHER.hash("Initial-pw-12345"), false);
        u.mustChangePassword = false;
        u.settingsComplete = settingsComplete;
        u.persist();
    }

    @Test
    @TestSecurity(user = "incomplete", roles = {"user"})
    void meDashboardRedirectsToSetupWhenIncomplete() {
        seed("incomplete", false);
        given().redirects().follow(false).when().get("/me")
            .then().statusCode(302).header("Location", containsString("/me/setup"));
    }

    @Test
    @TestSecurity(user = "incomplete2", roles = {"user"})
    void setupPageItselfIsReachableWhileIncomplete() {
        seed("incomplete2", false);
        given().redirects().follow(false).when().get("/me/setup")
            .then().statusCode(200); // must NOT redirect to itself
    }

    @Test
    @TestSecurity(user = "complete", roles = {"user", "admin"})
    void completedUserReachesMeNormally() {
        seed("complete", true);
        given().redirects().follow(false).when().get("/me")
            .then().statusCode(200); // dashboard, no redirect
    }
}
