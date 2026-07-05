package site.asm0dey.calit.oidc;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OidcLoginPageTest {

    // Default test profile has calit.oidc.enabled=false, so the SSO button must NOT render.
    @Test
    void loginPage_hidesSsoButton_whenDisabled() {
        given().when().get("/login").then().statusCode(200).body(not(containsString("/api/oidc/login")));
    }

    // With OIDC off, the code mechanism is inactive: a direct hit is unauthorized (no redirect to a provider).
    @Test
    void ssoLoginPath_isUnauthorized_whenDisabled() {
        given().redirects().follow(false).when().get("/api/oidc/login").then().statusCode(401);
    }
}
