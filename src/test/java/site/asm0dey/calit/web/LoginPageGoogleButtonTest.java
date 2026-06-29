package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class LoginPageGoogleButtonTest {

    @Test
    void loginPageOffersGoogleSignInWhenConfigured() {
        // %test sets google.oauth.client-id=test-client-id, so the button shows.
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("/api/google/login"))
                .body(containsString("Sign in with Google"));
    }

    @Test
    void loginPageShowsGoogleNotice() {
        given().when()
                .get("/login?notice=google_signup_disabled")
                .then()
                .statusCode(200)
                .body(containsString("sign-ups are disabled"));
    }
}
