package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Literal path segments must win over the /{user} and /{user}/{slug} templates. None of these
 * literals may be captured as a username. The Usernames reserved set is defence-in-depth; signup
 * /create in Phase 4 rejects reserved words. RESTEasy Reactive resolves literals first.
 */
@QuarkusTest
class ReservedRouteTest {

    @Test
    void loginIsTheLoginPageNotAUserLanding() {
        given().when()
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("j_security_check"))
                .body(not(containsString("Book a meeting")));
    }

    @Test
    void logoutIsHandledByItsLiteralResource() {
        given().redirects()
                .follow(false)
                .when()
                .get("/logout")
                .then()
                .statusCode(anyOf(is(302), is(303), is(200)))
                // Even if logout renders a page (200), it must not be a captured /{user} landing.
                .body(not(containsString("Book a meeting")));
    }

    @Test
    void quarkusHealthEndpointIsNotCapturedAsUser() {
        given().when().get("/q/health").then().statusCode(200).body(containsString("UP"));
    }

    @Test
    void googleConnectIsAuthGuardedNotAUserLanding() {
        given().redirects()
                .follow(false)
                .when()
                .get("/api/google/connect")
                .then()
                .statusCode(anyOf(is(302), is(401), is(303)));
    }

    @Test
    void bookingManageTokenRouteIsNotCapturedAsUser() {
        given().when()
                .get("/booking/no-such-token/manage")
                .then()
                .statusCode(404)
                .body(not(containsString("Book a meeting")));
    }

    @Test
    void meRequiresAuthAndIsNotAUserLanding() {
        given().redirects().follow(false).when().get("/me").then().statusCode(anyOf(is(302), is(303), is(401)));
    }
}
