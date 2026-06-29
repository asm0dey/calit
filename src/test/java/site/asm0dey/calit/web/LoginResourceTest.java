package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LoginResourceTest {

    @Test
    void anonymousSeesLoginForm() {
        given().redirects()
                .follow(false)
                .when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("j_security_check"));
    }

    @Test
    @TestSecurity(
            user = "admin",
            roles = {"user", "admin"})
    void authenticatedUserIsRedirectedToDashboard() {
        // Already signed in -> /login must bounce to /me, not render the form again.
        given().redirects()
                .follow(false)
                .when()
                .get("/login")
                .then()
                .statusCode(anyOf(is(302), is(303)))
                .header("Location", containsString("/me"));
    }
}
