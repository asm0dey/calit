package com.calit.user;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SetupFlowTest {

    private void deleteAllUsers() {
        QuarkusTransaction.requiringNew().run(() -> AppUser.deleteAll());
    }

    /**
     * Restore the bootstrapped baseline after each method so this class never leaves the shared
     * test DB at zero users — otherwise a later test class's public/admin request would be
     * redirected to /setup by FirstRunRedirectFilter. Mirrors {@code TestUserBootstrap}'s seed.
     */
    @AfterEach
    void restoreBaseline() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.count() == 0) {
                AppUser.create("admin", new PasswordHasher().hash("testpass"), true).persist();
            }
        });
    }

    private void seedOneUser() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (AppUser.count() == 0) {
                AppUser.create("existing", new PasswordHasher().hash("pw12345"), true).persist();
            }
        });
    }

    @Test
    void setupFormRendersWhenNoUsers() {
        deleteAllUsers();
        given().when().get("/setup")
                .then().statusCode(200)
                .body(containsString("Create the first user"))
                .body(containsString("name=\"username\""));
    }

    @Test
    void requestsRedirectToSetupWhenNoUsers() {
        deleteAllUsers();
        given().redirects().follow(false)
                .when().get("/me")
                .then().statusCode(302)
                .header("Location", containsString("/setup"));
    }

    @Test
    void publicLandingStaysOpenWhenNoUsers() {
        // The marketing landing at / is exempt from the first-run redirect — it must render even
        // before the instance is bootstrapped, not 302 to /setup.
        deleteAllUsers();
        given().redirects().follow(false)
                .when().get("/")
                .then().statusCode(200)
                .body(containsString("actually own"));
    }

    @Test
    void setupCreatesFirstAdminUserThenRedirectsToLogin() {
        deleteAllUsers();
        given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "Boss")
                .formParam("password", "boss-pw-123")
                .when().post("/setup")
                .then().statusCode(302)
                .header("Location", containsString("/login"));

        given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("j_username", "boss")
                .formParam("j_password", "boss-pw-123")
                .when().post("/j_security_check")
                .then().statusCode(302);
    }

    @Test
    void setupReturns404OnceAUserExists() {
        seedOneUser();
        given().when().get("/setup").then().statusCode(404);
        given().redirects().follow(false)
                .contentType("application/x-www-form-urlencoded")
                .formParam("username", "second")
                .formParam("password", "whatever-12")
                .when().post("/setup")
                .then().statusCode(404);
    }
}
