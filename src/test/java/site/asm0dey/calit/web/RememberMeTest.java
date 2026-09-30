package site.asm0dey.calit.web;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RememberMeTest {
    private Cookie loginCookie(boolean remember) {
        var req = given()
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("j_username", "admin")
            .formParam("j_password", "testpass");
        if (remember) {
            req = req.queryParam("remember", "true");
        }
        return req.when().post("/j_security_check").then().extract().detailedCookie("quarkus-credential");
    }

    @Test
    void rememberMeMakesCredentialCookiePersistent() {
        Cookie c = loginCookie(true);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge())
            .as("Expected positive Max-Age for persistent cookie, got: " + c.getMaxAge())
            .isGreaterThan(0);
    }

    @Test
    void withoutRememberCredentialCookieIsSessionScoped() {
        Cookie c = loginCookie(false);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).as("Expected -1 Max-Age for session cookie").isEqualTo(-1L);
    }
}
