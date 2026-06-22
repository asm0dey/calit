package site.asm0dey.calit.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class LogoutTest {

    @Test
    void logoutClearsCredentialCookieAndRedirectsToLogin() {
        given().redirects().follow(false)
            .cookie("quarkus-credential", FormAuth.login())
            .when().get("/logout")
            .then()
                .statusCode(303)
                .header("Location", containsString("/login"))
                // A cleared cookie is sent back (Max-Age=0).
                .header("Set-Cookie", containsString("quarkus-credential="))
                .header("Set-Cookie", containsString("Max-Age=0"));
    }

    @Test
    void adminNavOffersLogout() {
        given().cookie("quarkus-credential", FormAuth.login())
            .when().get("/me")
            .then().statusCode(200)
                .body(containsString("/logout"))
                .body(containsString("Log out"));
    }
}
