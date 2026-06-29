package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminAuthTest {

    @Test
    void dashboardRequiresAuth() {
        given().redirects()
                .follow(false)
                .when()
                .get("/me")
                .then()
                .statusCode(302)
                .header("Location", org.hamcrest.Matchers.containsString("/login"));
    }

    @Test
    void dashboardServedWhenLoggedIn() {
        given().cookie("quarkus-credential", FormAuth.login())
                .when()
                .get("/me")
                .then()
                .statusCode(200)
                .body(containsString("Dashboard"));
    }

    @Test
    void loginPageRenders() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("j_username"))
                .body(containsString("Sign in"));
    }
}
